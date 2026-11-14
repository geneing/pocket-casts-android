package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdAnalysisDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdSegmentDao
import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
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
 * On-device transcription for downloaded episodes.
 *
 * Speech recognition and the transcript cache stay on the device, but the classification step is
 * delegated to the remote jev classifier (next step), so this analyzer never owns an episode: [run]
 * transcribes and caches, then reports `false` so
 * [au.com.shiftyjelly.pocketcasts.repositories.ads.AudioAdAnalyzer] keeps producing ad segments
 * from the audio path until the classifier lands.
 *
 * The transcript is cached by [LocalTranscriptStore] and reused while the audio file, the local
 * transcript identity and the recognizer version are unchanged. Analysis state left behind by the
 * removed TF-IDF detector is invalidated so its segments disappear from existing databases.
 */
@Singleton
class TranscriptAdAnalyzer @Inject constructor(
    private val episodeManager: EpisodeManager,
    private val adSegmentDao: AdSegmentDao,
    private val adAnalysisDao: AdAnalysisDao,
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
     * the audio analyzer can take over when transcription is unavailable. Ownership is never
     * claimed while classification is remote, so the caller always falls back to audio.
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
        val signature = AnalysisIdentity.audioSignature(localFile)
        val sttVersion = transcriber.version
        val state = adAnalysisDao.findState(episodeUuid)
        if (AnalysisIdentity.isTranscriptAnalysis(state)) {
            Timber.tag(TAG).d("Invalidating stale transcript analysis for %s", episodeUuid)
            withContext(NonCancellable) {
                adSegmentDao.replaceAll(episodeUuid, emptyList())
                transcriptStore.delete(episodeUuid)
                adAnalysisDao.delete(episodeUuid)
            }
        } else if (transcriptStore.hasTranscript(episodeUuid) && state.recordsTranscriptOf(signature, sttVersion)) {
            Timber.tag(TAG).d("Episode %s already has a transcript for this audio and recognizer", episodeUuid)
            return false
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
        // TODO: plug the remote jev classifier in here, using the cached transcript as its input.
        return false
    }

    /**
     * True when the stored analysis row does not contradict a cached transcript. The audio
     * analyzer also writes [AdAnalysisState] rows for the same episode and those carry no
     * transcript identity, so their empty fields mean "unknown" rather than a mismatch.
     */
    private fun AdAnalysisState?.recordsTranscriptOf(signature: String, recognizerVersion: String): Boolean {
        if (this == null) return true
        if (audioSignature.isNotEmpty() && audioSignature != signature) return false
        if (modelVersion.isNotEmpty() && modelVersion != TRANSCRIPT_IDENTITY) return false
        return sttVersion.isEmpty() || sttVersion == recognizerVersion
    }

    companion object {
        private const val TAG = "TranscriptAdAnalyzer"

        // Identity of the on-device transcript cache, replaced by the jev classifier identity later.
        private const val TRANSCRIPT_IDENTITY = "transcript-cache-v1"
    }
}
