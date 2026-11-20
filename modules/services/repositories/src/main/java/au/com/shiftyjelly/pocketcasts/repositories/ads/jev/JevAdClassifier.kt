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
     * Per-cue ad-break probability and per-cue content probability, in cue order and aligned with
     * the cues that were classified; complete, so no cue is `null`.
     */
    data class Scored(
        val pAd: List<Double?>,
        val pContent: List<Double?>,
        val responseModel: String?,
        val usageCostUsd: Double?,
    ) : JevClassification

    /** No API key is configured, so the remote classifier cannot run at all. */
    data class Unavailable(val reason: String) : JevClassification

    /** No window was scored, or some cues came back unanswered; nothing is written. */
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
 * A cue's score is the probability that it belongs to an ad break, which includes a `transition`
 * verdict: an introduction to or a return from a break is skipped with the break itself, so its
 * own probability counts as well as `advertisement`.
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
        val pContent = MutableList<Double?>(cues.size) { null }
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
                            pAd[index] = score.adBreakProbability
                            pContent[index] = score.contentProbability
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
        if (unclassified > 0) {
            // A partial result would be written as a complete analysis and then reused, so the
            // unclassified cues would never be retried. Fail instead and let the caller fall back
            // to the audio analyser, which still produces segments for this episode.
            return JevClassification.Failed(
                "$unclassified of ${pAd.size} cues were not classified ($failedWindows of $windowCount windows failed)",
            )
        }
        return JevClassification.Scored(
            pAd = pAd,
            pContent = pContent,
            responseModel = responseModel,
            usageCostUsd = cost.takeIf { it > 0.0 },
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
 * A seed only marks where an ad is certain. [pContent] is then used to decide where the break
 * around it ends: each edge grows over the cues that still lean break rather than content, which is
 * the spoken hand-over before the read and the return after it, and stops at the first cue content
 * explains better. That rule needs no extra threshold and no fixed number of sentences; [maxEdgeMs]
 * is only a guard so one long low-confidence stretch cannot swallow the show. Without [pContent]
 * the pods are the seeds alone.
 *
 * The one-cue gap tolerance follows the labelled-corpus evaluation: whisper cues straddle pod
 * edges, so a single content-scored cue inside an ad read is expected rather than a real break.
 * Pure, so the threshold, edge and pod rules are testable without a network or a database.
 */
fun buildPods(
    cues: List<TranscriptCue>,
    pAd: List<Double?>,
    pContent: List<Double?> = emptyList(),
    threshold: Float = AD_PROBABILITY_THRESHOLD,
    maxGapCues: Int = MAX_GAP_CUES,
    minPodMs: Long = MIN_POD_MS,
    maxEdgeMs: Long = MAX_EDGE_MS,
): List<JevPod> {
    require(cues.size == pAd.size) { "cues and probabilities must be the same length" }
    require(pContent.isEmpty() || pContent.size == cues.size) { "content probabilities must match the cues" }
    require(maxGapCues >= 0) { "maxGapCues must be non-negative" }

    val runs = seedRuns(pAd, threshold, maxGapCues)
        .map { seed -> extendRun(cues, pAd, pContent, seed, maxEdgeMs) }
        .fold(mutableListOf<IntRange>()) { merged, run ->
            val previous = merged.lastOrNull()
            if (previous != null && run.first <= previous.last + 1) {
                merged[merged.lastIndex] = previous.first..run.last
            } else {
                merged += run
            }
            merged
        }
    return runs.map { run -> podOf(cues, pAd, run.first, run.last) }
        .filter { it.endMs - it.startMs >= minPodMs }
}

/** The runs of cues at or above [threshold], bridging gaps of at most [maxGapCues] cues. */
private fun seedRuns(pAd: List<Double?>, threshold: Float, maxGapCues: Int): List<IntRange> {
    val runs = mutableListOf<IntRange>()
    var first = -1
    var last = -1
    var gap = 0
    for (index in pAd.indices) {
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
            runs += first..last
            first = -1
            gap = 0
        }
    }
    if (first >= 0) {
        runs += first..last
    }
    return runs
}

/**
 * Grows [seed] outwards over adjacent cues that belong to the break. A cue is part of the break
 * when its ad-break probability beats its content probability, so the walk ends where the show
 * resumes instead of after a fixed number of sentences.
 */
private fun extendRun(
    cues: List<TranscriptCue>,
    pAd: List<Double?>,
    pContent: List<Double?>,
    seed: IntRange,
    maxEdgeMs: Long,
): IntRange {
    if (pContent.isEmpty()) return seed
    var start = seed.first
    while (start > 0 &&
        extendsBreak(pAd[start - 1], pContent[start - 1]) &&
        cues[seed.first].startMs - cues[start - 1].startMs <= maxEdgeMs
    ) {
        start--
    }
    var end = seed.last
    while (end < cues.lastIndex &&
        extendsBreak(pAd[end + 1], pContent[end + 1]) &&
        cues[end + 1].endMs - cues[seed.last].endMs <= maxEdgeMs
    ) {
        end++
    }
    return start..end
}

/** True when the cue is more likely part of the break than of the show's editorial. */
private fun extendsBreak(pAd: Double?, pContent: Double?): Boolean = pAd != null && pContent != null && pAd > pContent

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

/**
 * How far a pod edge may grow outwards, in milliseconds.
 *
 * A guard, not the rule: the edge is decided by whether the cue reads as break or content, and
 * this only stops one long ambiguous stretch from swallowing the show.
 */
const val MAX_EDGE_MS = 30_000L
