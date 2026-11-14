package au.com.shiftyjelly.pocketcasts.repositories.ai

import androidx.annotation.VisibleForTesting
import au.com.shiftyjelly.pocketcasts.repositories.di.JevApiKey
import au.com.shiftyjelly.pocketcasts.servers.di.NoCache
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.IOException
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * OpenRouter chat completions client for the AI tab.
 *
 * One POST asks one question, with the episode transcript in the system message:
 *
 * ```
 * POST https://openrouter.ai/api/v1/chat/completions
 * Authorization: Bearer <key>
 * Content-Type: application/json
 * {"model": "deepseek/deepseek-v4.1-flash-1017",
 *  "messages": [{"role": "system", "content": "<instructions>\n\nEpisode transcript:\n<transcript>"},
 *               {"role": "user", "content": "Summarize the podcast"}],
 *  "max_tokens": 1024}
 * ```
 *
 * The answer is `choices[0].message.content`. The model also returns `message.reasoning`, which is
 * its scratchpad and is ignored.
 *
 * The call is enqueued rather than executed on a worker thread, so cancelling the caller cancels
 * the HTTP call. The client never throws to its caller: a missing key becomes
 * [AiChatResult.NoApiKey], and any transport, HTTP or JSON problem becomes [AiChatResult.Failed]
 * with the key scrubbed out of the reason.
 */
@Singleton
class OpenRouterChatClient @Inject constructor(
    @NoCache private val okHttpClient: OkHttpClient,
    @JevApiKey private val apiKey: Optional<String>,
    moshi: Moshi,
) : AiChatClient {
    @VisibleForTesting
    internal var endpoint: String = CHAT_COMPLETIONS_ENDPOINT

    private val requestAdapter = moshi.adapter(AiChatRequest::class.java)
    private val responseAdapter = moshi.adapter(AiChatResponseBody::class.java)

    @Volatile
    private var missingKeyReported = false

    override suspend fun ask(
        question: String,
        transcript: String,
        history: List<AiChatMessage>,
    ): AiChatResult = withContext(Dispatchers.IO) {
        val key = apiKey.orElse("").trim()
        if (key.isEmpty()) {
            if (!missingKeyReported) {
                missingKeyReported = true
                LogBuffer.i(TAG, "No OpenRouter API key configured; the AI tab is unavailable")
            }
            return@withContext AiChatResult.NoApiKey
        }
        val asked = question.trim()
        if (asked.isEmpty()) return@withContext AiChatResult.Failed("empty question")

        val payload = runCatching { requestAdapter.toJson(buildRequest(asked, transcript, history)) }
            .getOrElse { error ->
                LogBuffer.e(TAG, error, "Could not serialise the AI chat request")
                return@withContext AiChatResult.Failed("request serialisation failed")
            }
        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .build()

        val response = execute(request) ?: return@withContext AiChatResult.Failed("network failure")
        if (response.code !in 200..299) {
            LogBuffer.e(
                TAG,
                "OpenRouter returned HTTP ${response.code} for ${AiChatPrompt.MODEL}: " +
                    scrub(response.body.take(LOG_BODY_CHARS), key),
            )
            return@withContext AiChatResult.Failed(scrub("HTTP ${response.code}", key))
        }
        parse(response.body, key)
    }

    private fun buildRequest(
        question: String,
        transcript: String,
        history: List<AiChatMessage>,
    ): AiChatRequest = AiChatRequest(
        model = AiChatPrompt.MODEL,
        messages = buildList {
            add(AiChatRequestMessage(role = SYSTEM_ROLE, content = AiChatPrompt.systemContent(transcript)))
            history.forEach { message ->
                add(AiChatRequestMessage(role = message.role.wireValue, content = message.content))
            }
            add(AiChatRequestMessage(role = AiChatMessage.Role.User.wireValue, content = question))
        },
        maxTokens = AiChatPrompt.MAX_TOKENS,
    )

    /**
     * Enqueues [request] and suspends until it answers, so the caller's cancellation cancels the
     * HTTP call instead of leaving it running. Returns null when the request could not be made.
     */
    private suspend fun execute(request: Request): RawResponse? = suspendCancellableCoroutine { continuation ->
        val call = okHttpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    LogBuffer.e(TAG, e, "Could not reach $endpoint")
                    continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val raw = response.use {
                        RawResponse(
                            code = it.code,
                            body = runCatching { it.body.string() }.getOrDefault(""),
                        )
                    }
                    if (continuation.isActive) continuation.resume(raw)
                }
            },
        )
    }

    private fun parse(body: String, key: String): AiChatResult {
        val parsed = runCatching { responseAdapter.fromJson(body) }
            .getOrElse { error ->
                LogBuffer.e(TAG, error, "Malformed OpenRouter chat response")
                return AiChatResult.Failed("malformed response")
            }
            ?: return AiChatResult.Failed("empty response")
        val content = parsed.choices?.firstOrNull()?.message?.content?.trim()
        if (content.isNullOrEmpty()) {
            LogBuffer.e(TAG, "OpenRouter chat response has no message content")
            return AiChatResult.Failed("response has no message content")
        }
        return AiChatResult.Answer(text = content, costUsd = parsed.usage?.cost)
    }

    /** Nothing here should ever carry the key, but a failure reason must not either. */
    private fun scrub(text: String, key: String): String {
        return if (key.isEmpty()) text else text.replace(key, REDACTED)
    }

    private class RawResponse(val code: Int, val body: String)

    companion object {
        private const val TAG = "OpenRouterChatClient"
        const val CHAT_COMPLETIONS_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        private const val SYSTEM_ROLE = "system"
        private const val REDACTED = "[redacted]"
        private const val LOG_BODY_CHARS = 300
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

@JsonClass(generateAdapter = true)
internal data class AiChatRequest(
    val model: String,
    val messages: List<AiChatRequestMessage>,
    @Json(name = "max_tokens") val maxTokens: Int,
)

@JsonClass(generateAdapter = true)
internal data class AiChatRequestMessage(
    val role: String,
    val content: String,
)

@JsonClass(generateAdapter = true)
internal data class AiChatResponseBody(
    val model: String? = null,
    val choices: List<AiChatChoice>? = null,
    val usage: AiChatUsage? = null,
)

/** Only `message.content` is used; `reasoning` is ignored by Moshi. */
@JsonClass(generateAdapter = true)
internal data class AiChatChoice(val message: AiChatResponseMessage? = null)

@JsonClass(generateAdapter = true)
internal data class AiChatResponseMessage(
    val role: String? = null,
    val content: String? = null,
)

/** Only `cost` is used; token counts are ignored by Moshi. */
@JsonClass(generateAdapter = true)
internal data class AiChatUsage(val cost: Double? = null)
