package au.com.shiftyjelly.pocketcasts.repositories.ads.jev

import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptCue
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Outcome of scoring one episode's transcript with jev. */
sealed interface JevClassification {
    /**
     * Per-cue advertisement probability, in cue order. A cue is `null` when no answer came back,
     * which [buildPods] treats as not an ad.
     */
    data class Scored(
        val pAd: List<Double?>,
        val responseModel: String?,
        val usageCostUsd: Double?,
        val unclassifiedCues: Int,
    ) : JevClassification

    /** No API key is configured, so the remote classifier cannot run at all. */
    data class Unavailable(val reason: String) : JevClassification

    /** Every window failed; nothing was scored. */
    data class Failed(val reason: String) : JevClassification
}

/** A run of ad-scored cues, in the media timeline of the analysed audio. */
data class JevPod(
    val firstCue: Int,
    val lastCue: Int,
    val startMs: Long,
    val endMs: Long,
    val meanAdvertisementProbability: Float,
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Scores a transcript cue by cue with jev and merges the result into ad pods.
 *
 * Cues go out in non-overlapping windows of [WINDOW_CUES], one request per window and one window
 * at a time: the analyzer's mutex already guarantees one episode at a time, and sequential
 * requests keep the cost predictable on a metered connection.
 */
@Singleton
class JevAdClassifier @Inject constructor(
    private val client: JevClient,
) {
    suspend fun classify(
        cues: List<TranscriptCue>,
        podcastTitle: String,
        model: JevAdModel,
    ): JevClassification {
        if (cues.isEmpty()) return JevClassification.Failed("no cues to classify")
        val pAd = MutableList<Double?>(cues.size) { null }
        var responseModel: String? = null
        var cost = 0.0
        var failedWindows = 0
        var windowCount = 0

        for (start in cues.indices step WINDOW_CUES) {
            currentCoroutineContext().ensureActive()
            val stop = minOf(start + WINDOW_CUES, cues.size)
            windowCount++
            val sentences = (start until stop).map { index ->
                JevSentence(id = index - start + 1, text = cues[index].text)
            }
            when (val response = client.classify(model, podcastTitle, sentences)) {
                is JevResponse.Scored -> {
                    responseModel = response.responseModel ?: responseModel
                    cost += response.usageCostUsd ?: 0.0
                    for ((id, score) in response.sentences) {
                        val index = start + id - 1
                        if (index in pAd.indices) {
                            pAd[index] = score.advertisementProbability
                        }
                    }
                }

                is JevResponse.Unavailable -> return JevClassification.Unavailable(response.reason)

                is JevResponse.Failed -> {
                    failedWindows++
                    LogBuffer.e(TAG, "jev window $start..$stop of ${cues.size} failed: ${response.reason}")
                }
            }
        }

        val unclassified = pAd.count { it == null }
        if (unclassified == pAd.size) {
            return JevClassification.Failed("all $windowCount classification windows failed")
        }
        if (failedWindows > 0) {
            val scored = pAd.size - unclassified
            LogBuffer.i(TAG, "jev classified $scored of ${pAd.size} cues ($failedWindows windows failed)")
        }
        return JevClassification.Scored(
            pAd = pAd,
            responseModel = responseModel,
            usageCostUsd = cost.takeIf { it > 0.0 },
            unclassifiedCues = unclassified,
        )
    }

    companion object {
        private const val TAG = "JevAdClassifier"

        /** Sentences per request. Verified for both models; context across the window is required. */
        const val WINDOW_CUES = 20
    }
}

/**
 * Builds ad pods from per-cue probabilities: keep cues at or above [threshold], bridge runs that
 * are separated by at most [maxGapCues] non-ad cues, and drop pods shorter than [minPodMs].
 *
 * The one-cue gap tolerance follows the labelled-corpus evaluation: whisper cues straddle pod
 * edges, so a single content-scored cue inside an ad read is expected rather than a real break.
 * Pure, so the threshold and pod rules are testable without a network or a database.
 */
fun buildPods(
    cues: List<TranscriptCue>,
    pAd: List<Double?>,
    threshold: Float = AD_PROBABILITY_THRESHOLD,
    maxGapCues: Int = MAX_GAP_CUES,
    minPodMs: Long = MIN_POD_MS,
): List<JevPod> {
    require(cues.size == pAd.size) { "cues and probabilities must be the same length" }
    require(maxGapCues >= 0) { "maxGapCues must be non-negative" }

    val pods = mutableListOf<JevPod>()
    var first = -1
    var last = -1
    var gap = 0
    for (index in cues.indices) {
        val probability = pAd[index]
        if (probability != null && probability >= threshold) {
            if (first < 0) {
                first = index
                gap = 0
            }
            last = index
            continue
        }
        if (first < 0) continue
        gap++
        if (gap > maxGapCues) {
            pods += podOf(cues, pAd, first, last)
            first = -1
            gap = 0
        }
    }
    if (first >= 0) {
        pods += podOf(cues, pAd, first, last)
    }
    return pods.filter { it.endMs - it.startMs >= minPodMs }
}

private fun podOf(
    cues: List<TranscriptCue>,
    pAd: List<Double?>,
    first: Int,
    last: Int,
): JevPod {
    var sum = 0.0
    var count = 0
    for (index in first..last) {
        val probability = pAd[index] ?: continue
        sum += probability
        count++
    }
    return JevPod(
        firstCue = first,
        lastCue = last,
        startMs = cues[first].startMs,
        endMs = cues[last].endMs,
        meanAdvertisementProbability = if (count == 0) 0f else (sum / count).toFloat(),
    )
}

/** A cue is an ad when `p_ad >= 0.5`; this is the threshold the corpus evaluation reports. */
const val AD_PROBABILITY_THRESHOLD = 0.5f

/** Cues between two ad cues that may be bridged when merging a pod. */
const val MAX_GAP_CUES = 1

/** Predicted pods shorter than this are dropped. */
const val MIN_POD_MS = 8_000L
