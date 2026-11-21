package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.models.db.dao.AdAnalysisDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdCueScoreDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdSegmentDao
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState
import au.com.shiftyjelly.pocketcasts.models.to.AdSegmentKind
import au.com.shiftyjelly.pocketcasts.models.to.AdSegmentOrigin
import au.com.shiftyjelly.pocketcasts.models.to.DbAdCueScore
import au.com.shiftyjelly.pocketcasts.models.to.DbAdSegment
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevAdClassifier
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevAdModelStore
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevClassification
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevPrompt
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.buildPods
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What one detection pass did for an episode. */
sealed interface DetectionOutcome {
    /** The whole transcript is scored and the ASR pass is finished. */
    data object Completed : DetectionOutcome

    /** A new window was scored; the rest waits for the recognizer or the next pass. */
    data object Progressed : DetectionOutcome

    /** Not enough stable cues yet; the recognizer will wake detection when it writes more. */
    data object Waiting : DetectionOutcome

    /** Transient failure (network, service); the queue row holds the retry time. */
    data object Retry : DetectionOutcome

    /** Nothing to do for this episode. */
    data object NotQueued : DetectionOutcome
}

/**
 * Scores the on-device transcript in windows as it arrives, and rebuilds the ad pods from every cue
 * scored so far.
 *
 * Only the transcript written by [LocalTranscriptStore] is read: a published transcript has wrong
 * timings and misses ad reads, which is exactly what made the old path skip the wrong audio. The
 * newest cues are held back by [STABILITY_MARGIN_CUES] until the recognizer finishes, because a
 * mid-sentence VAD split can still merge the last line into its predecessor; a cue that is scored
 * before that merge would leave a stale score behind.
 *
 * Every request is resumable: the queue row stores the end of the last scored cue, and a window
 * older than [CONTEXT_CUES] is only re-read for context, not re-sent. A failed window leaves the
 * stored scores untouched, so the next attempt starts at the same cue.
 */
@Singleton
class TranscriptAdAnalyzer @Inject constructor(
    private val episodeManager: EpisodeManager,
    private val podcastManager: PodcastManager,
    private val adSegmentDao: AdSegmentDao,
    private val adAnalysisDao: AdAnalysisDao,
    private val adCueScoreDao: AdCueScoreDao,
    private val classifier: JevAdClassifier,
    private val transcriber: EpisodeTranscriber,
    private val transcriptStore: LocalTranscriptStore,
    private val jevAdModelStore: JevAdModelStore,
    private val queue: AdAnalysisQueue,
) {
    private val mutex = Mutex()

    suspend fun run(episodeUuid: String): DetectionOutcome = mutex.withLock { runAnalysis(episodeUuid) }

    private suspend fun runAnalysis(episodeUuid: String): DetectionOutcome {
        if (queue.detectionItem(episodeUuid) == null) return DetectionOutcome.NotQueued
        val asr = queue.asrItem(episodeUuid)
        val cues = transcriptStore.readCues(episodeUuid)
        val asrComplete = asr?.isComplete != false
        if (cues.isEmpty()) {
            if (asrComplete) {
                queue.recordDetectionProgress(episodeUuid, 0L, isComplete = true)
                return DetectionOutcome.Completed
            }
            return waiting(episodeUuid)
        }

        val episode = episodeManager.findByUuid(episodeUuid) ?: run {
            queue.forget(episodeUuid)
            return DetectionOutcome.NotQueued
        }
        val podcast = podcastManager.findPodcastByUuid(episode.podcastUuid)
        resetIfStale(episodeUuid, episode)

        val stableSize = if (asrComplete) cues.size else (cues.size - STABILITY_MARGIN_CUES).coerceAtLeast(0)
        if (stableSize == 0) return waiting(episodeUuid)
        val stable = cues.subList(0, stableSize)
        val storedScores = adCueScoreDao.findScoresForEpisode(episodeUuid)
            .filter { it.cueIndex in stable.indices }
            .associateBy { it.cueIndex }

        // A hole can appear when an earlier pass stored a score but the window request failed
        // later; resuming at the first hole heals it instead of leaving one cue unscored forever.
        val startIndex = stable.indices.firstOrNull { index -> storedScores[index] == null } ?: stableSize
        if (startIndex >= stableSize) {
            if (asrComplete) {
                markComplete(episodeUuid, stable)
                return DetectionOutcome.Completed
            }
            return waiting(episodeUuid)
        }
        val newCues = stableSize - startIndex
        if (!asrComplete && newCues < MIN_BATCH_CUES) return waiting(episodeUuid)

        val windowStart = (startIndex - CONTEXT_CUES).coerceAtLeast(0)
        val window = stable.subList(windowStart, stableSize)
        val model = jevAdModelStore.selectedJevAdModel()
        val podcastTitle = podcast?.title.orEmpty().ifBlank { UNKNOWN_SHOW }

        val scored = when (val classification = classifier.classify(window, podcastTitle, model)) {
            is JevClassification.Unavailable -> {
                LogBuffer.i(TAG, "jev classification unavailable for $episodeUuid: ${classification.reason}")
                queue.failDetection(episodeUuid, classification.reason, UNAVAILABLE_RETRY_MS)
                return DetectionOutcome.Retry
            }

            is JevClassification.Failed -> {
                LogBuffer.e(TAG, "jev classification failed for $episodeUuid: ${classification.reason}")
                queue.failDetection(episodeUuid, classification.reason, NETWORK_RETRY_MS)
                return DetectionOutcome.Retry
            }

            is JevClassification.Scored -> classification
        }

        val pAd = MutableList<Double?>(stableSize) { index -> storedScores[index]?.probability?.toDouble() }
        val pContent = MutableList<Double?>(stableSize) { index -> storedScores[index]?.contentProbability?.toDouble() }
        scored.pAd.forEachIndexed { index, probability ->
            val cueIndex = windowStart + index
            if (cueIndex in pAd.indices) pAd[cueIndex] = probability
        }
        scored.pContent.forEachIndexed { index, probability ->
            val cueIndex = windowStart + index
            if (cueIndex in pContent.indices) pContent[cueIndex] = probability
        }

        val now = System.currentTimeMillis()
        val totalMs = stable.last().endMs
        val stored = buildPods(stable, pAd).mapIndexedNotNull { index, pod ->
            val start = pod.startMs.coerceIn(0L, totalMs)
            val end = pod.endMs.coerceIn(0L, totalMs)
            if (end - start < MIN_POD_MS) {
                null
            } else {
                DbAdSegment(
                    index = index,
                    episodeUuid = episodeUuid,
                    startTimeMs = start,
                    endTimeMs = end,
                    kind = AdSegmentKind.Ad,
                    origin = AdSegmentOrigin.Transcript,
                    confidence = pod.meanAdvertisementProbability.coerceIn(0f, 1f),
                    createdAt = now,
                )
            }
        }
        val cueScores = stable.mapIndexedNotNull { index, cue ->
            val probability = pAd[index] ?: return@mapIndexedNotNull null
            DbAdCueScore(
                episodeUuid = episodeUuid,
                cueIndex = index,
                startTimeMs = cue.startMs,
                endTimeMs = cue.endMs,
                probability = probability.toFloat().coerceIn(0f, 1f),
                contentProbability = pContent[index]?.toFloat()?.coerceIn(0f, 1f),
            )
        }
        val complete = asrComplete && pAd.all { it != null }
        val signature = episode.audioSignature()
        val modelVersion = AnalysisIdentity.transcriptModelVersion(scored.responseModel, model, JevPrompt.VERSION)

        var podCount = 0
        withContext(NonCancellable) {
            val previous = adSegmentDao.findSegmentsForEpisode(episodeUuid)
            val deselected = previous.filter { it.deselected }.associateBy { it.startTimeMs / 1000 }
            val merged = stored.map { segment ->
                val previousChoice = deselected[segment.startTimeMs / 1000]
                if (previousChoice != null) segment.copy(deselected = true) else segment
            }
            podCount = merged.size
            adSegmentDao.replaceAll(episodeUuid, merged)
            adCueScoreDao.replaceAll(episodeUuid, cueScores)
            adAnalysisDao.upsert(
                AdAnalysisState(
                    episodeUuid = episodeUuid,
                    analyzedUntilMs = totalMs,
                    isComplete = complete,
                    updatedAt = System.currentTimeMillis(),
                    audioSignature = signature.orEmpty(),
                    modelVersion = modelVersion,
                    sttVersion = transcriber.version,
                ),
            )
            queue.recordDetectionProgress(episodeUuid, totalMs, isComplete = complete)
        }
        LogBuffer.i(
            TAG,
            "Transcript ad detection for $episodeUuid: cues $windowStart..$stableSize scored, " +
                "$podCount pods, complete=$complete, costUsd=${scored.usageCostUsd}",
        )
        return if (complete) DetectionOutcome.Completed else DetectionOutcome.Progressed
    }

    private suspend fun markComplete(episodeUuid: String, cues: List<TranscriptCue>) {
        val state = adAnalysisDao.findState(episodeUuid)
        val episodes = episodeManager.findByUuid(episodeUuid)
        adAnalysisDao.upsert(
            AdAnalysisState(
                episodeUuid = episodeUuid,
                analyzedUntilMs = cues.lastOrNull()?.endMs ?: 0L,
                isComplete = true,
                updatedAt = System.currentTimeMillis(),
                audioSignature = episodes?.audioSignature().orEmpty(),
                modelVersion = state?.modelVersion.orEmpty(),
                sttVersion = transcriber.version,
            ),
        )
        queue.recordDetectionProgress(episodeUuid, cues.lastOrNull()?.endMs ?: 0L, isComplete = true)
    }

    /** Parks the episode until the recognizer writes more cues; not a failure, so attempts stay. */
    private suspend fun waiting(episodeUuid: String): DetectionOutcome {
        queue.deferDetection(episodeUuid, WAITING_RETRY_MS)
        return DetectionOutcome.Waiting
    }

    /**
     * A transcript made from a published VTT or by another recognizer is not this analysis: its
     * cues may have different timing and its scores may point at the wrong sentences. Clearing the
     * derived rows restarts detection over the recognized transcript.
     *
     * A different prompt revision or classifier model is just as stale: its scores describe
     * sentences under another rule, so the stored segments would be served with the old edges.
     */
    private suspend fun resetIfStale(episodeUuid: String, episode: BaseEpisode) {
        val state = queue.analysisState(episodeUuid) ?: return
        val signature = episode.audioSignature()
        val audioChanged = signature != null && state.audioSignature.isNotBlank() && state.audioSignature != signature
        val model = jevAdModelStore.selectedJevAdModel()
        val ruleChanged = state.modelVersion.isNotBlank() &&
            !AnalysisIdentity.isTranscriptModelVersion(state.modelVersion, model, JevPrompt.VERSION)
        // A re-download can carry a new ad stitch while the engine stays the same, so the audio on
        // its own is enough to discard scores that point at sentences the listener no longer hears.
        if (state.sttVersion == transcriber.version && !audioChanged && !ruleChanged) return
        LogBuffer.i(
            TAG,
            "Discarding ad analysis for $episodeUuid (stt=${state.sttVersion.ifBlank { "audio" }}, " +
                "audioChanged=$audioChanged, rule=${state.modelVersion.ifBlank { "none" }} -> ${model.slug}#prompt${JevPrompt.VERSION})",
        )
        queue.resetDetection(episodeUuid)
    }

    private fun BaseEpisode.audioSignature(): String? {
        val file = downloadedFilePath?.let(::File)?.takeIf { it.isFile }
        if (file != null) return AnalysisIdentity.audioSignature(file)
        return streamUrl?.takeIf { it.isNotBlank() }?.let { "stream:${it.hashCode()}" }
    }

    private companion object {
        const val TAG = "TranscriptAdAnalyzer"
        const val MIN_POD_MS = 8_000L
        const val UNKNOWN_SHOW = "Unknown podcast"

        /** New lines the recognizer may merge into the previous cue before they are scored. */
        const val STABILITY_MARGIN_CUES = 2

        /** Cues re-read before the first unscored one so a pod edge is not judged out of context. */
        const val CONTEXT_CUES = 4

        /** Smallest useful batch while recognition is still running; a request per line costs more. */
        const val MIN_BATCH_CUES = 10

        const val NETWORK_RETRY_MS = 10 * 60_000L
        const val UNAVAILABLE_RETRY_MS = 60 * 60_000L
        const val WAITING_RETRY_MS = 5 * 60_000L
    }
}
