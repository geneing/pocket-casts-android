package au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote

import android.util.Base64
import androidx.annotation.VisibleForTesting
import au.com.shiftyjelly.pocketcasts.repositories.di.OpenRouterApiKey
import au.com.shiftyjelly.pocketcasts.servers.di.NoCache
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.IOException
import java.util.Optional
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** One word the remote model placed on the audio clock. */
data class RemoteWord(val word: String, val startSeconds: Double, val endSeconds: Double)

/** One sentence the remote model placed on the audio clock. */
data class RemoteSegment(val text: String, val startSeconds: Double, val endSeconds: Double)

/** What one request returned, in the audio clock of the chunk it was given. */
data class RemoteTranscript(
    val words: List<RemoteWord>,
    val segments: List<RemoteSegment>,
    /** The whole transcript as one string, which is all some providers return. */
    val text: String = "",
    val audioSeconds: Double,
    /** What the provider charged for this request, or null when it did not report a price. */
    val costUsd: Double?,
    val language: String?,
)

sealed interface RemoteSttResult {
    data class Success(val transcript: RemoteTranscript) : RemoteSttResult

    /** No OpenRouter key is configured in this build, so there is nothing to call. */
    data object NoApiKey : RemoteSttResult

    /** A transport, HTTP or response problem. The pass can be retried as it is. */
    data class Failed(val message: String) : RemoteSttResult
}

/**
 * The OpenRouter speech-to-text endpoint: one POST per chunk of episode audio.
 *
 * ```
 * POST https://openrouter.ai/api/v1/audio/transcriptions
 * Authorization: Bearer <key>
 * {"model": "nvidia/nemotron-3.5-asr-streaming-multilingual-0.6b",
 *  "input_audio": {"data": "<base64 m4a>", "format": "m4a"},
 *  "response_format": "verbose_json",
 *  "timestamp_granularities": ["word", "segment"],
 *  "language": "en"}
 * ```
 *
 * The audio is uploaded inline because this model's provider refuses `source_url` ("The selected
 * model does not support audio from a URL"), and OpenRouter has no streaming variant of this
 * endpoint: `stream: true` is accepted and ignored. There is no `pcm` input either. What the
 * endpoint does take is a container, up to a 50 MiB request body, which is why the pass encodes
 * each chunk as mono AAC in an m4a: 32 kbit/s is 4 kB/s of audio, so even a 20 minute chunk stays
 * two orders of magnitude below the limit.
 *
 * `verbose_json` returns `words[]` with start and end times from this model, which is what makes
 * sentence cues and ad boundaries possible; the default response is text only.
 *
 * The call is enqueued rather than executed on a worker thread, so cancelling the caller cancels
 * the HTTP request. The client never throws: a missing key becomes [RemoteSttResult.NoApiKey] and
 * any other problem becomes [RemoteSttResult.Failed] with the key scrubbed out of the reason.
 */
@Singleton
class RemoteSttClient @Inject constructor(
    @NoCache okHttpClient: OkHttpClient,
    @OpenRouterApiKey private val apiKey: Optional<String>,
    moshi: Moshi,
) {
    @VisibleForTesting
    internal var endpoint: String = TRANSCRIPTIONS_ENDPOINT

    private val requestAdapter = moshi.adapter(RemoteSttRequest::class.java)
    private val responseAdapter = moshi.adapter(RemoteSttResponse::class.java)

    /**
     * A chunk of a ten minute episode is roughly 24 s of recognition on the provider, and the
     * upload has to land first, so the shared client's default timeouts are too short. They are
     * raised on a copy so no other caller of the shared client picks up the longer waits.
     */
    private val httpClient = okHttpClient.newBuilder()
        .callTimeout(CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = apiKey.orElse("").isNotBlank()

    suspend fun transcribe(audio: ByteArray, format: String, language: String?): RemoteSttResult {
        val key = apiKey.orElse("").trim()
        if (key.isEmpty()) return RemoteSttResult.NoApiKey
        if (audio.isEmpty()) return RemoteSttResult.Failed("empty audio chunk")

        val payload = runCatching {
            requestAdapter.toJson(
                RemoteSttRequest(
                    model = MODEL,
                    inputAudio = RemoteSttInputAudio(
                        data = Base64.encodeToString(audio, Base64.NO_WRAP),
                        format = format,
                    ),
                    responseFormat = VERBOSE_JSON,
                    timestampGranularities = TIMESTAMP_GRANULARITIES,
                    language = language,
                ),
            )
        }.getOrElse { error ->
            LogBuffer.e(TAG, error, "Could not serialise the transcription request")
            return RemoteSttResult.Failed("request serialisation failed")
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .header("HTTP-Referer", APP_REFERER)
            .header("X-Title", APP_TITLE)
            .build()

        val response = execute(request) ?: return RemoteSttResult.Failed("network failure")
        if (response.code !in 200..299) {
            val body = scrub(response.body.take(LOG_BODY_CHARS), key)
            LogBuffer.e(TAG, "OpenRouter returned HTTP ${response.code} for $MODEL: $body")
            return RemoteSttResult.Failed("HTTP ${response.code}: $body")
        }
        return parse(response.body, key)
    }

    private fun parse(body: String, key: String): RemoteSttResult {
        val parsed = runCatching { responseAdapter.fromJson(body) }
            .getOrElse { error ->
                LogBuffer.e(TAG, error, "Malformed transcription response")
                return RemoteSttResult.Failed("malformed response")
            }
            ?: return RemoteSttResult.Failed("empty response")

        // An error object can arrive with a 200 from an upstream provider; it is not a transcript.
        if (parsed.text == null && parsed.error != null) {
            return RemoteSttResult.Failed(scrub("provider error: ${parsed.error.message}", key))
        }
        val transcript = RemoteTranscript(
            words = parsed.words.orEmpty().mapNotNull { word ->
                val text = word.word?.trim().orEmpty()
                if (text.isEmpty() || word.start == null || word.end == null) return@mapNotNull null
                RemoteWord(text, word.start, word.end)
            },
            segments = parsed.segments.orEmpty().mapNotNull { segment ->
                val text = segment.text?.trim().orEmpty()
                if (text.isEmpty() || segment.start == null || segment.end == null) return@mapNotNull null
                RemoteSegment(text, segment.start, segment.end)
            },
            text = parsed.text.orEmpty().trim(),
            // verbose_json reports the decoded duration; the fallback is the billed one, which is
            // the audio the pass actually sent.
            audioSeconds = parsed.duration ?: parsed.usage?.seconds ?: 0.0,
            costUsd = parsed.usage?.cost,
            language = parsed.language,
        )
        if (transcript.words.isEmpty() && transcript.segments.isEmpty() && parsed.text.isNullOrBlank()) {
            LogBuffer.e(TAG, "Transcription response carried no text")
            return RemoteSttResult.Failed("response has no text")
        }
        return RemoteSttResult.Success(transcript)
    }

    private suspend fun execute(request: Request): RawResponse? = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
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

    /** Nothing here should ever carry the key, but a failure reason must not either. */
    private fun scrub(text: String, key: String): String = if (key.isEmpty()) text else text.replace(key, REDACTED)

    private class RawResponse(val code: Int, val body: String)

    companion object {
        private const val TAG = "RemoteAsr"

        /** The model whose words the ad analysis is scored against. */
        const val MODEL = "nvidia/nemotron-3.5-asr-streaming-multilingual-0.6b"

        private const val TRANSCRIPTIONS_ENDPOINT = "https://openrouter.ai/api/v1/audio/transcriptions"
        private const val VERBOSE_JSON = "verbose_json"
        private const val APP_REFERER = "https://pocketcasts.com"
        private const val APP_TITLE = "Pocket Casts"
        private const val REDACTED = "[redacted]"
        private const val LOG_BODY_CHARS = 300
        private const val CALL_TIMEOUT_MINUTES = 15L
        private const val READ_TIMEOUT_SECONDS = 600L
        private const val WRITE_TIMEOUT_SECONDS = 300L
        private val TIMESTAMP_GRANULARITIES = listOf("word", "segment")
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

@JsonClass(generateAdapter = true)
internal data class RemoteSttRequest(
    val model: String,
    @Json(name = "input_audio") val inputAudio: RemoteSttInputAudio,
    @Json(name = "response_format") val responseFormat: String,
    @Json(name = "timestamp_granularities") val timestampGranularities: List<String>,
    val language: String?,
)

@JsonClass(generateAdapter = true)
internal data class RemoteSttInputAudio(
    val data: String,
    val format: String,
)

@JsonClass(generateAdapter = true)
internal data class RemoteSttResponse(
    val text: String? = null,
    val language: String? = null,
    val duration: Double? = null,
    val segments: List<RemoteSttSegmentBody>? = null,
    val words: List<RemoteSttWordBody>? = null,
    val usage: RemoteSttUsage? = null,
    val error: RemoteSttError? = null,
)

@JsonClass(generateAdapter = true)
internal data class RemoteSttSegmentBody(
    val text: String? = null,
    val start: Double? = null,
    val end: Double? = null,
)

@JsonClass(generateAdapter = true)
internal data class RemoteSttWordBody(
    val word: String? = null,
    val start: Double? = null,
    val end: Double? = null,
)

@JsonClass(generateAdapter = true)
internal data class RemoteSttUsage(
    val seconds: Double? = null,
    /** The request's price in USD. Absent when the provider does not report one. */
    val cost: Double? = null,
)

@JsonClass(generateAdapter = true)
internal data class RemoteSttError(
    val message: String? = null,
    val code: Int? = null,
)
