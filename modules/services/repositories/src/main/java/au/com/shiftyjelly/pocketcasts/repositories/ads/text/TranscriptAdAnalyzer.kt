package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdAnalysisDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdSegmentDao
import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState
import au.com.shiftyjelly.pocketcasts.models.to.AdSegment
import au.com.shiftyjelly.pocketcasts.models.to.AdSegmentKind
import au.com.shiftyjelly.pocketcasts.models.to.AdSegmentOrigin
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Ad analysis for downloaded episodes: on-device speech recognition, then the remote jev
 * choice classifier over the transcript.
 *
 * The transcript is cached by [LocalTranscriptStore] and reused while the audio file, the local
 * transcript identity, the recognizer version and the jev model (including its prompt revision)
 * are unchanged. Pods are written as [DbAdSegment]s with [AdSegmentOrigin.Transcript].
 *
 * Confidence is deliberately capped below [AdSegment.MIN_AUTO_SKIP_CONFIDENCE]: the model is not
 * calibrated and the on-device cue timings are only speech-region accurate, so every transcript
 * pod stays a suggestion until an independently labelled evaluation clears the product bar.
 *
 * When the classifier is unavailable or fails, [run] reports `false` without writing state, so
 * [au.com.shiftyjelly.pocketcasts.repositories.ads.AudioAdAnalyzer] still produces segments.
 */
@Singleton
class TranscriptAdAnalyzer @Inject constructor(
    private val episodeManager: EpisodeManager,
    private val podcastManager: PodcastManager,
    private val adSegmentDao: AdSegmentDao,
    private val adAnalysisDao: AdAnalysisDao,
    private val classifier: JevAdClassifier,
    private val transcriber: EpisodeTranscriber,
    private val transcriptStore: LocalTranscriptStore,
    private val jevAdModelStore: JevAdModelStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var analysisJob: Job? = null

    fun isSupported(): Boolean = transcriber.isAvailable()

    fun analyze(episodeUuid: String) {
        val previous = analysisJob
        previous?.cancel()
        analysisJob = scope.launch(Dispatchers.IO.limitedParallelism(1)) {
            runCatching { previous?.join() }
            mutex.withLock { runAnalysis(episodeUuid) }
        }
    }

    /**
     * Runs inside the caller's coroutine and reports whether this analyzer owned the episode, so
     * the audio analyzer can take over when transcription or classification is unavailable.
     */
    suspend fun run(episodeUuid: String): Boolean = mutex.withLock { runAnalysis(episodeUuid) }

    private suspend fun runAnalysis(episodeUuid: String): Boolean {
        val startedAt = System.currentTimeMillis()
        val episode = episodeManager.findByUuid(episodeUuid) ?: return false
        val localFile = episode.downloadedFilePath?.let(::File)?.takeIf { it.exists() }
        if (localFile == null) {
            Timber.tag(TAG).d("Episode %s is not downloaded; transcription is file-only", episodeUuid)
            return false
        }
        if (!transcriber.isAvailable()) return false

        val model = jevAdModelStore.selectedJevAdModel()
        val signature = AnalysisIdentity.audioSignature(localFile)
        val sttVersion = transcriber.version
        val state = adAnalysisDao.findState(episodeUuid)
        val hasTranscript = transcriptStore.hasTranscript(episodeUuid)
        if (hasTranscript &&
            AnalysisIdentity.isReusableTranscript(state, signature, sttVersion, model, JevPrompt.VERSION)
        ) {
            Timber.tag(TAG).d("Episode %s is already analysed with this audio, model and recognizer", episodeUuid)
            return true
        }
        if (AnalysisIdentity.isTranscriptAnalysis(state)) {
            Timber.tag(TAG).d("Invalidating stale transcript analysis for %s", episodeUuid)
            withContext(NonCancellable) {
                adSegmentDao.replaceAll(episodeUuid, emptyList())
                transcriptStore.delete(episodeUuid)
                adAnalysisDao.delete(episodeUuid)
            }
        }

        val result = transcriber.transcribe(episodeUuid, localFile, episode.durationMs.toLong())
        val cues = when (result) {
            is TranscriptResult.Success -> result.cues

            is TranscriptResult.Unavailable -> {
                LogBuffer.i(TAG, "On-device transcription unavailable: ${result.reason}")
                return false
            }

            is TranscriptResult.Failed -> {
                LogBuffer.e(TAG, "Transcription failed for $episodeUuid: ${result.message}")
                return false
            }
        }
        if (cues.isEmpty()) return false

        transcriptStore.save(episodeUuid, cues)
        LogBuffer.i(
            TAG,
            "Cached transcript for $episodeUuid: ${cues.size} cues, " +
                "${System.currentTimeMillis() - startedAt}ms",
        )

        val podcastTitle = podcastManager.findPodcastByUuid(episode.podcastUuid)?.title.orEmpty()
        val classification = classifier.classify(cues, podcastTitle.ifBlank { UNKNOWN_SHOW }, model)
        val scored = when (classification) {
            is JevClassification.Unavailable -> {
                LogBuffer.i(TAG, "jev classification unavailable for $episodeUuid: ${classification.reason}")
                return false
            }

            is JevClassification.Failed -> {
                LogBuffer.e(TAG, "jev classification failed for $episodeUuid: ${classification.reason}")
                return false
            }

            is JevClassification.Scored -> classification
        }

        val totalMs = cues.maxOf { it.endMs }
        val now = System.currentTimeMillis()
        val stored = buildPods(cues, scored.pAd).mapIndexedNotNull { index, pod ->
            val start = pod.startMs.coerceIn(0, totalMs)
            val end = pod.endMs.coerceIn(0, totalMs)
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
                    confidence = pod.meanAdvertisementProbability
                        .coerceAtMost(AdSegment.MIN_AUTO_SKIP_CONFIDENCE - CONFIDENCE_MARGIN),
                    createdAt = now,
                )
            }
        }
        val modelVersion = AnalysisIdentity.transcriptModelVersion(scored.responseModel, model, JevPrompt.VERSION)
        val costUsd = scored.usageCostUsd

        withContext(NonCancellable) {
            val existing = adSegmentDao.findSegmentsForEpisode(episodeUuid)
            val deselected = existing.filter { it.deselected }.associateBy { it.startTimeMs / 1000 }
            val merged = stored.map { segment ->
                val previousChoice = deselected[segment.startTimeMs / 1000]
                if (previousChoice != null) segment.copy(deselected = true) else segment
            }
            adSegmentDao.replaceAll(episodeUuid, merged)
            adAnalysisDao.upsert(
                AdAnalysisState(
                    episodeUuid = episodeUuid,
                    analyzedUntilMs = totalMs,
                    isComplete = true,
                    updatedAt = System.currentTimeMillis(),
                    audioSignature = signature,
                    modelVersion = modelVersion,
                    sttVersion = sttVersion,
                ),
            )
            LogBuffer.i(
                TAG,
                "Transcript ad analysis for $episodeUuid: ${cues.size} cues, ${merged.size} pods, " +
                    "costUsd=$costUsd, ${System.currentTimeMillis() - startedAt}ms",
            )
        }
        return true
    }

    companion object {
        private const val TAG = "TranscriptAdAnalyzer"
        private const val MIN_POD_MS = 8_000L
        private const val UNKNOWN_SHOW = "Unknown podcast"

        // Keeps every pod strictly below AdSegment.MIN_AUTO_SKIP_CONFIDENCE; the model is not calibrated.
        private const val CONFIDENCE_MARGIN = 0.01f
    }
}
