package au.com.shiftyjelly.pocketcasts.repositories.ads.jev

import androidx.annotation.VisibleForTesting
import au.com.shiftyjelly.pocketcasts.repositories.di.OpenRouterApiKey
import au.com.shiftyjelly.pocketcasts.servers.di.NoCache
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.IOException
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Client for OpenRouter's System One choice classifier.
 *
 * One POST asks one multiple-choice question per sentence:
 *
 * ```
 * POST https://openrouter.ai/api/v1/systemone
 * Authorization: Bearer <key>
 * Content-Type: application/json
 * {"model": "respan/span-02",
 *  "state": {"podcast": "<show>", "sentences": [{"id": 1, "text": "..."}]},
 *  "questions": {"sentence_1": {"type": "choice", "instructions": "...", "criteria": {...}}}}
 * ```
 *
 * and the answer for each question carries `probabilities["advertisement"]`, which is the signal
 * this client returns. The endpoint is not deterministic, there is no seed or temperature control,
 * and `/chat/completions` rejects both models, so the URL is fixed.
 *
 * The client never throws to its caller: a missing key becomes [JevResponse.Unavailable], and any
 * transport, HTTP or JSON problem becomes [JevResponse.Failed] or a partial [JevResponse.Scored].
 */
@Singleton
class JevClient @Inject constructor(
    @NoCache private val okHttpClient: OkHttpClient,
    @OpenRouterApiKey private val apiKey: Optional<String>,
    moshi: Moshi,
) {
    @VisibleForTesting
    internal var endpoint: String = SYSTEM_ONE_ENDPOINT

    private val requestAdapter = moshi.adapter(JevRequest::class.java)
    private val responseAdapter = moshi.adapter(JevResponseBody::class.java)

    @Volatile
    private var missingKeyReported = false

    /**
     * Classifies [sentences] with [model] in one request. Sentences are always sent together: a
     * sentence is judged with its neighbours as context, and one question is asked per sentence.
     */
    suspend fun classify(
        model: JevAdModel,
        podcast: String,
        sentences: List<JevSentence>,
    ): JevResponse = withContext(Dispatchers.IO) { classifyBlocking(model, podcast, sentences) }

    private fun classifyBlocking(
        model: JevAdModel,
        podcast: String,
        sentences: List<JevSentence>,
    ): JevResponse {
        val key = apiKey.orElse("").trim()
        if (key.isEmpty()) {
            if (!missingKeyReported) {
                missingKeyReported = true
                LogBuffer.i(TAG, "No OpenRouter API key configured; jev ad classification is unavailable")
            }
            return JevResponse.Unavailable("no API key")
        }
        if (sentences.isEmpty()) return JevResponse.Failed("no sentences to classify")

        val payload = runCatching { requestAdapter.toJson(buildRequest(model, podcast, sentences)) }
            .getOrElse { error ->
                LogBuffer.e(TAG, error, "Could not serialise the jev request for ${model.slug}")
                return JevResponse.Failed("request serialisation failed")
            }
        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .build()

        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: IOException) {
            LogBuffer.e(TAG, e, "Could not reach $endpoint")
            return JevResponse.Failed("network failure: ${e.message}")
        }
        return response.use {
            val body = runCatching { it.body.string() }.getOrDefault("")
            if (!it.isSuccessful) {
                LogBuffer.e(TAG, "System One returned HTTP ${it.code} for ${model.slug}")
                return@use JevResponse.Failed("HTTP ${it.code}")
            }
            parse(body, sentences)
        }
    }

    private fun buildRequest(
        model: JevAdModel,
        podcast: String,
        sentences: List<JevSentence>,
    ): JevRequest = JevRequest(
        model = model.slug,
        state = JevState(podcast = podcast, sentences = sentences),
        questions = sentences.associate { sentence ->
            "sentence_${sentence.id}" to JevQuestion(
                type = TYPE_CHOICE,
                instructions = JevPrompt.instructions(sentence.id),
                criteria = JevPrompt.CRITERIA,
            )
        },
    )

    private fun parse(body: String, sentences: List<JevSentence>): JevResponse {
        val parsed = runCatching { responseAdapter.fromJson(body) }
            .getOrElse { error ->
                LogBuffer.e(TAG, error, "Malformed System One response")
                return JevResponse.Failed("malformed response")
            }
            ?: return JevResponse.Failed("empty response")
        val answers = parsed.answers
            ?: run {
                LogBuffer.e(TAG, "System One response has no answers object")
                return JevResponse.Failed("response has no answers")
            }

        val scores = LinkedHashMap<Int, JevSentenceScore>(sentences.size)
        var missing = 0
        for (sentence in sentences) {
            val answer = answers["sentence_${sentence.id}"]
            if (answer == null) {
                missing++
                continue
            }
            scores[sentence.id] = JevSentenceScore(
                label = answer.choice,
                advertisementProbability = answer.advertisementProbability(),
                contentProbability = answer.contentProbability(),
                transitionProbability = answer.transitionProbability(),
            )
        }
        if (missing > 0) {
            LogBuffer.i(TAG, "System One answered ${sentences.size - missing} of ${sentences.size} sentences")
        }
        return JevResponse.Scored(
            sentences = scores,
            responseModel = parsed.model,
            usageCostUsd = parsed.usage?.cost,
        )
    }

    /** The contract's fallback: read `probabilities["advertisement"]`, else fall back to `choice`. */
    private fun JevAnswer.advertisementProbability(): Double? {
        probabilities?.get(JevPrompt.ADVERTISEMENT)?.let { return it }
        return when (choice) {
            JevPrompt.ADVERTISEMENT -> 1.0
            null -> null
            else -> 0.0
        }
    }

    /** The same fallback for `probabilities["content"]`, so a cue can be compared with its editorial probability. */
    private fun JevAnswer.contentProbability(): Double? {
        probabilities?.get(JevPrompt.CONTENT)?.let { return it }
        return when (choice) {
            JevPrompt.CONTENT -> 1.0
            null -> null
            else -> 0.0
        }
    }

    /**
     * The same fallback for `probabilities["transition"]`. A response that carries no transition
     * probability but answers `transition` reads as 1.0: the label is the verdict, and an
     * introduction to or return from a break is skipped along with the break itself.
     */
    private fun JevAnswer.transitionProbability(): Double? {
        probabilities?.get(JevPrompt.TRANSITION)?.let { return it }
        return when (choice) {
            JevPrompt.TRANSITION -> 1.0
            null -> null
            else -> 0.0
        }
    }

    companion object {
        private const val TAG = "JevClient"
        const val SYSTEM_ONE_ENDPOINT = "https://openrouter.ai/api/v1/systemone"
        private const val TYPE_CHOICE = "choice"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/**
 * The prompt is part of the analysis identity: bump [VERSION] whenever the wording, the way a
 * label is scored or the rule that turns scores into pods changes, so segments produced under the
 * old behaviour are re-classified instead of served.
 */
object JevPrompt {
    const val VERSION = 4

    const val ADVERTISEMENT = "advertisement"
    const val CONTENT = "content"
    const val TRANSITION = "transition"

    /**
     * Each label is defined by the sentence's job in the episode rather than its tone, because a
     * host-read sponsor line opens like show talk and a hand-over line is ordinary speech.
     */
    val CRITERIA: Map<String, String> = linkedMapOf(
        ADVERTISEMENT to "The paid read itself: naming or addressing the sponsor, its offer, claims, " +
            "price, instructions, call to action, or the closing terms and disclaimer. A sentence at " +
            "the seam that mixes the ad with the show belongs here.",
        CONTENT to "The show's own editorial discussion, outside every advertising break. A brand " +
            "mentioned in passing with no offer or call to action is content.",
        TRANSITION to "The spoken hand-over that opens or closes an advertising break and sells " +
            "nothing: announcing a break, naming the sponsor as a hand-over, or returning the listener " +
            "to the show. It is part of the break.",
    )

    fun instructions(sentenceId: Int): String = "Classify only the sentence with id $sentenceId. Decide whether it belongs to an advertising " +
        "break, not whether it sounds like an ad: the break starts at its spoken hand-over and ends " +
        "when the show resumes, and every sentence between those two points is part of it. Use the " +
        "other sentences only to locate those two edges. Ignore every other sentence: they are " +
        "separate questions."
}

/** One sentence of a window, with an id that is local to the request. */
@JsonClass(generateAdapter = true)
data class JevSentence(val id: Int, val text: String)

/** The classifier's verdict for one sentence. */
data class JevSentenceScore(
    val label: String?,
    val advertisementProbability: Double?,
    val contentProbability: Double?,
    val transitionProbability: Double?,
) {
    /**
     * Probability that the sentence belongs to an ad break. A transition — an introduction to or a
     * return from a break — is skipped along with the break, so it counts at its own probability
     * and a `transition` label without probabilities still skips the sentence.
     */
    val adBreakProbability: Double?
        get() = when {
            advertisementProbability == null -> transitionProbability
            transitionProbability == null -> advertisementProbability
            else -> maxOf(advertisementProbability, transitionProbability)
        }
}

/** Outcome of one classification request. A missing answer is left out of [Scored.sentences]. */
sealed interface JevResponse {
    data class Scored(
        val sentences: Map<Int, JevSentenceScore>,
        val responseModel: String?,
        val usageCostUsd: Double?,
    ) : JevResponse

    /** No API key is configured, so no request was made. */
    data class Unavailable(val reason: String) : JevResponse

    /** The request was made or attempted and produced nothing usable. */
    data class Failed(val reason: String) : JevResponse
}

@JsonClass(generateAdapter = true)
internal data class JevRequest(
    val model: String,
    val state: JevState,
    val questions: Map<String, JevQuestion>,
)

@JsonClass(generateAdapter = true)
internal data class JevState(
    val podcast: String,
    val sentences: List<JevSentence>,
)

@JsonClass(generateAdapter = true)
internal data class JevQuestion(
    val type: String,
    val instructions: String,
    val criteria: Map<String, String>,
)

@JsonClass(generateAdapter = true)
internal data class JevResponseBody(
    val model: String? = null,
    val answers: Map<String, JevAnswer>? = null,
    val usage: JevUsage? = null,
)

@JsonClass(generateAdapter = true)
internal data class JevAnswer(
    val choice: String? = null,
    val probabilities: Map<String, Double>? = null,
)

/** Only `cost` is used; `input_tokens`, `output_tokens` and `is_byok` are ignored by Moshi. */
@JsonClass(generateAdapter = true)
internal data class JevUsage(val cost: Double? = null)
