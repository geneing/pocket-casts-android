package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdAnalysisDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdSegmentDao
import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState
import au.com.shiftyjelly.pocketcasts.models.to.AdSegmentKind
import au.com.shiftyjelly.pocketcasts.models.to.AdSegmentOrigin
import au.com.shiftyjelly.pocketcasts.models.to.DbAdSegment
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Ad analysis for downloaded episodes using on-device speech recognition and the transcript
 * TF-IDF detector. Runs off the playback thread, caches the transcript, and writes pods as
 * [DbAdSegment]s with [AdSegmentOrigin.Transcript].
 *
 * Confidence is deliberately kept below the auto-skip threshold: the research model is
 * suggestion-only until an independently labelled evaluation clears the product bar.
 */
@Singleton
class TranscriptAdAnalyzer @Inject constructor(
    private val episodeManager: EpisodeManager,
    private val adSegmentDao: AdSegmentDao,
    private val adAnalysisDao: AdAnalysisDao,
    private val detector: AdTextDetector,
    private val transcriber: EpisodeTranscriber,
    private val transcriptStore: LocalTranscriptStore,
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
     * the audio analyzer can take over when transcription is unavailable.
     */
    suspend fun run(episodeUuid: String): Boolean = mutex.withLock { runAnalysis(episodeUuid) }

    private suspend fun runAnalysis(episodeUuid: String): Boolean {
        val startedAt = System.currentTimeMillis()
        val episode = episodeManager.findByUuid(episodeUuid) ?: return false
        val localFile = episode.downloadedFilePath?.let(::File)?.takeIf { it.exists() }
        if (localFile == null) {
            Timber.tag(TAG).d("Episode %s is not downloaded; transcript analysis is file-only", episodeUuid)
            return false
        }
        if (!transcriber.isAvailable()) return false
        val model = detector.model() ?: run {
            LogBuffer.e(TAG, "Ad model unavailable; skipping transcript analysis for $episodeUuid")
            return false
        }
        val signature = AnalysisIdentity.audioSignature(localFile)
        val sttVersion = transcriber.version
        val state = adAnalysisDao.findState(episodeUuid)
        val reusable = AnalysisIdentity.isReusable(state, signature, model.identity, sttVersion) &&
            transcriptStore.hasTranscript(episodeUuid)
        if (reusable) {
            Timber.tag(TAG).d("Episode %s already analysed with this audio, model and recognizer", episodeUuid)
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
        val segments = cues.map {
            AdTranscriptSegment(it.startMs / 1000.0, it.endMs / 1000.0, it.text)
        }
        val pods = model.detect(segments)
        val totalMs = cues.maxOf { it.endMs }
        val confidence = SUGGESTION_CONFIDENCE
        val stored = pods.mapIndexedNotNull { index, pod ->
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
                    confidence = confidence,
                    createdAt = System.currentTimeMillis(),
                )
            }
        }

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
                    modelVersion = model.identity,
                    sttVersion = sttVersion,
                ),
            )
            LogBuffer.i(
                TAG,
                "Transcript ad analysis for $episodeUuid: ${cues.size} cues, ${merged.size} pods, " +
                    "${System.currentTimeMillis() - startedAt}ms",
            )
        }
        return true
    }

    companion object {
        private const val TAG = "TranscriptAdAnalyzer"
        private const val MIN_POD_MS = 8_000L

        // Below AdSegment.AUTO_SKIP_CONFIDENCE; the model is not calibrated.
        private const val SUGGESTION_CONFIDENCE = 0.4f
    }
}
