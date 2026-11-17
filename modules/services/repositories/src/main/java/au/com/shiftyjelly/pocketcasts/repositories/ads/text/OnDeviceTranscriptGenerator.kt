package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState
import au.com.shiftyjelly.pocketcasts.repositories.file.FileStorage
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What one recognition pass did for an episode. */
sealed interface AsrOutcome {
    /** The whole episode is recognised; detection can finish. */
    data object Completed : AsrOutcome

    /** The pass stopped early but its cues are stored; try again from the last cue later. */
    data object Retry : AsrOutcome

    /** The speech model has to be fetched and the network is metered; ask again on Wi-Fi. */
    data object Deferred : AsrOutcome

    /** Nothing can be recognised here (no audio, unsupported language, unavailable engine). */
    data object Unsupported : AsrOutcome

    /** The episode is no longer in the queue. */
    data object NotQueued : AsrOutcome
}

/**
 * Recognises one episode on the device, writing every completed line as it arrives.
 *
 * A pass is resumable by construction: it starts at the end of the last stored cue, and each
 * partial result is written before the next line is recognised. A killed worker, a decoder error or
 * a dropped network therefore loses at most the line in flight, never the whole pass.
 */
@Singleton
class OnDeviceTranscriptGenerator @Inject constructor(
    private val episodeManager: EpisodeManager,
    private val podcastManager: PodcastManager,
    private val transcriber: EpisodeTranscriber,
    private val transcriptStore: LocalTranscriptStore,
    private val fileStorage: FileStorage,
    private val queue: AdAnalysisQueue,
) {
    private val mutex = Mutex()

    /** Cue count when detection was last woken, and when; reset for every pass. */
    private var lastDetectionCueCount = 0
    private var lastDetectionAt = 0L

    fun isSupported(): Boolean = transcriber.isAvailable()

    suspend fun run(
        episodeUuid: String,
        onModelProgress: (ModelDownloadProgress) -> Unit = {},
    ): AsrOutcome = mutex.withLock {
        try {
            runPass(episodeUuid, onModelProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A row that throws before it can record its own failure is picked again on every pass,
            // and the worker drains in arrival order, so one bad episode would keep every episode
            // added after it from ever running. Recording the failure is what lets the queue move on;
            // the pass itself is resumable, so the retry only redoes what was missing.
            LogBuffer.e(TAG, e, "Recognition of $episodeUuid threw before it could report")
            runCatching {
                queue.failAsr(episodeUuid, "crashed: ${e.javaClass.simpleName}: ${e.message}", ASR_RETRY_DELAY_MS)
            }
            AsrOutcome.Retry
        }
    }

    private suspend fun runPass(
        episodeUuid: String,
        onModelProgress: (ModelDownloadProgress) -> Unit,
    ): AsrOutcome {
        var item = queue.asrItem(episodeUuid) ?: return AsrOutcome.NotQueued
        if (item.isComplete) return AsrOutcome.Completed

        val episode = episodeManager.findByUuid(episodeUuid) ?: run {
            queue.forget(episodeUuid)
            return AsrOutcome.Unsupported
        }
        val podcast = podcastManager.findPodcastByUuid(episode.podcastUuid)
        val source = resolveSource(episode) ?: run {
            LogBuffer.i(TAG, "Episode $episodeUuid has no audio yet, trying again later")
            queue.failAsr(episodeUuid, "no audio source", ASR_RETRY_DELAY_MS)
            return AsrOutcome.Retry
        }

        // A re-download or an engine update makes the stored transcript and every score built from
        // it stale. Restarting here is what keeps the selected source of truth unambiguous.
        resetIfStale(episodeUuid, source)
        item = queue.asrItem(episodeUuid) ?: return AsrOutcome.NotQueued

        when (val availability = transcriber.availability(podcast?.podcastLanguage)) {
            TranscriptAvailability.Ready -> Unit

            TranscriptAvailability.ModelNeedsUnmeteredNetwork -> {
                LogBuffer.i(TAG, "Speech model download for $episodeUuid is waiting for an unmetered network")
                queue.deferAsr(episodeUuid, REASON_METERED)
                return AsrOutcome.Deferred
            }

            TranscriptAvailability.UnsupportedLanguage -> {
                LogBuffer.i(TAG, "No speech model for the language of $episodeUuid")
                abandon(episodeUuid, episode.durationMs.toLong())
                return AsrOutcome.Unsupported
            }

            TranscriptAvailability.EngineUnavailable -> {
                LogBuffer.i(TAG, "On-device speech recognition is unavailable, leaving $episodeUuid alone")
                abandon(episodeUuid, episode.durationMs.toLong())
                return AsrOutcome.Unsupported
            }
        }

        val existing = transcriptStore.readCues(episodeUuid)
        val startMs = maxOf(item.processedUntilMs, existing.lastOrNull()?.endMs ?: 0L)
        lastDetectionCueCount = existing.size
        lastDetectionAt = 0L
        val durationMs = episode.durationMs.toLong()
        if (durationMs > 0 && startMs >= durationMs - COMPLETE_MARGIN_MS) {
            complete(episodeUuid, startMs)
            return AsrOutcome.Completed
        }

        val result = transcriber.transcribe(
            request = TranscriptRequest(
                episodeUuid = episodeUuid,
                source = source,
                durationMs = durationMs,
                podcastLanguage = podcast?.podcastLanguage,
                startMs = startMs,
            ),
            onModelProgress = onModelProgress,
        ) { incoming ->
            val merged = mergeCues(existing, incoming, resumeAtMs = startMs)
            if (merged.size == existing.size && merged.lastOrNull() == existing.lastOrNull()) {
                return@transcribe
            }
            persist(episodeUuid, merged)
            if (shouldWakeDetection(merged.size)) {
                queue.enqueueDetectionNow(episodeUuid)
            }
        }

        return when (result) {
            is TranscriptResult.Success -> {
                val merged = mergeCues(existing, result.cues, resumeAtMs = startMs)
                persist(episodeUuid, merged)
                complete(episodeUuid, merged.lastOrNull()?.endMs ?: durationMs)
                LogBuffer.i(
                    TAG,
                    "Recognised $episodeUuid: ${merged.size} cues from ${startMs}ms, ${result.engine}",
                )
                AsrOutcome.Completed
            }

            is TranscriptResult.Unavailable -> {
                if (result.reason.contains("unmetered", ignoreCase = true)) {
                    queue.deferAsr(episodeUuid, result.reason)
                    AsrOutcome.Deferred
                } else {
                    LogBuffer.i(TAG, "On-device transcription unavailable for $episodeUuid: ${result.reason}")
                    // HLS or a missing file may become transcribable after a download; only an
                    // unsupported language or engine is final.
                    queue.failAsr(episodeUuid, result.reason, ASR_RETRY_DELAY_MS)
                    AsrOutcome.Retry
                }
            }

            is TranscriptResult.Failed -> {
                // Whatever was written before the failure is already stored and will be the resume
                // point; this only decides when the next attempt is allowed.
                LogBuffer.e(TAG, "Transcription failed for $episodeUuid: ${result.message}")
                // Score what was recognized before the failure, even if it is below a full batch:
                // the transcript tab is already showing it.
                queue.enqueueDetectionNow(episodeUuid)
                queue.failAsr(episodeUuid, result.message, ASR_RETRY_DELAY_MS)
                AsrOutcome.Retry
            }
        }
    }

    private suspend fun persist(episodeUuid: String, cues: List<TranscriptCue>) {
        if (cues.isEmpty()) return
        transcriptStore.saveProgress(episodeUuid, cues)
        queue.recordAsrProgress(episodeUuid, cues.last().endMs)
    }

    /**
     * Detection is woken in batches rather than per completed line: each wake is a worker run and,
     * once there is enough text, a paid classifier request. A batch of [DETECTION_BATCH_CUES] keeps
     * the pods current without a request per sentence; the final line always wakes it.
     */
    private fun shouldWakeDetection(cueCount: Int): Boolean {
        val now = System.currentTimeMillis()
        val enoughNewCues = cueCount - lastDetectionCueCount >= DETECTION_BATCH_CUES
        val overdue = lastDetectionAt > 0L && now - lastDetectionAt >= DETECTION_MAX_DELAY_MS
        if (!enoughNewCues && !overdue) return false
        lastDetectionCueCount = cueCount
        lastDetectionAt = now
        return true
    }

    private suspend fun complete(episodeUuid: String, processedUntilMs: Long) {
        transcriptStore.markComplete(episodeUuid, transcriber.version)
        queue.completeAsr(episodeUuid, processedUntilMs)
        queue.enqueueDetectionNow(episodeUuid)
    }

    /** Marks the queue row done without claiming the transcript is a usable recognition. */
    private suspend fun abandon(episodeUuid: String, processedUntilMs: Long) {
        queue.completeAsr(episodeUuid, processedUntilMs)
    }

    private suspend fun resetIfStale(episodeUuid: String, source: TranscriptSource) {
        val state: AdAnalysisState = queue.analysisState(episodeUuid) ?: return
        val signature = AnalysisIdentity.audioSignature(source)
        val staleAudio = state.audioSignature.isNotBlank() && state.audioSignature != signature
        val staleEngine = state.sttVersion.isNotBlank() && state.sttVersion != transcriber.version
        val fromPublishedTranscript = state.sttVersion.isNotBlank() && !state.sttVersion.startsWith(MOONSHINE_PREFIX)
        if (!staleAudio && !staleEngine && !fromPublishedTranscript) return
        LogBuffer.i(
            TAG,
            "Resetting transcript analysis for $episodeUuid " +
                "(audioChanged=$staleAudio, engineChanged=$staleEngine, published=$fromPublishedTranscript)",
        )
        queue.resetAnalysis(episodeUuid)
    }

    /**
     * Where to recognise from, preferring the downloaded file.
     *
     * The download worker asks for the analysis as soon as the file is written, before the episode
     * row records its path, so the recorded path is often still empty at this point. The download
     * location is deterministic from the episode uuid, and a downloaded episode whose file is not
     * resolvable yet is given a bounded wait for the row to catch up. An episode that is not
     * downloaded at all is recognised straight from its stream; an HLS stream travels with its
     * flag so the recognizer can refuse it instead of decoding a playlist.
     */
    private suspend fun resolveSource(episode: BaseEpisode): TranscriptSource? {
        resolveAudioFile(episode)?.let { return TranscriptSource.Downloaded(it) }
        if (episode.isDownloaded) {
            awaitAudioFile(episode.uuid)?.let { return TranscriptSource.Downloaded(it) }
        }
        return episode.streamUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { TranscriptSource.Stream(it, isHls = episode.isStreamUrlHls) }
    }

    private fun resolveAudioFile(episode: BaseEpisode): File? {
        val recorded = episode.downloadedFilePath?.let(::File)?.takeIf { it.isFile }
        if (recorded != null) return recorded
        return fileStorage.getOrCreatePodcastEpisodeFile(episode)?.takeIf { it.isFile && it.length() > 0L }
    }

    private suspend fun awaitAudioFile(episodeUuid: String): File? = withTimeoutOrNull(WAIT_FOR_DOWNLOAD_MS) {
        episodeManager.findEpisodeByUuidFlow(episodeUuid)
            .mapNotNull { resolveAudioFile(it) }
            .firstOrNull()
    }

    /**
     * Combines the cues stored before the pass with the snapshot the recognizer has produced so far.
     * The pass may start a keyframe before its resume point, so those overlapping cues are deduped
     * by start time instead of being appended twice; a cue that straddles the resume point keeps its
     * new (more complete) text.
     */
    internal fun mergeCues(
        existing: List<TranscriptCue>,
        incoming: List<TranscriptCue>,
        resumeAtMs: Long = 0L,
    ): List<TranscriptCue> {
        if (incoming.isEmpty()) return existing
        val merged = LinkedHashMap<Long, TranscriptCue>(existing.size + incoming.size)
        existing.forEach { cue -> merged[cue.startMs / DEDUPE_WINDOW_MS] = cue }
        // A resumed pass seeks to a keyframe before its resume point, so the audio it re-hears can
        // include cues that are already stored. Anything that ended before the stored prefix is not
        // new text, and a cue straddling the edge is clipped to it so its tail is not duplicated.
        val boundaryMs = maxOf(resumeAtMs, existing.lastOrNull()?.endMs ?: 0L)
        incoming.forEach { cue ->
            if (cue.endMs <= boundaryMs) return@forEach
            val clipped = if (cue.startMs < boundaryMs) cue.copy(startMs = boundaryMs) else cue
            merged[clipped.startMs / DEDUPE_WINDOW_MS] = clipped
        }
        val ordered = merged.values.sortedBy { it.startMs }
        val result = ArrayList<TranscriptCue>(ordered.size)
        var previousEndMs = 0L
        for (cue in ordered) {
            val startMs = maxOf(cue.startMs, previousEndMs)
            if (cue.endMs <= startMs) continue
            result.add(cue.copy(startMs = startMs))
            previousEndMs = cue.endMs
        }
        return result
    }

    private companion object {
        const val TAG = "OnDeviceTranscript"
        const val DEDUPE_WINDOW_MS = 250L
        const val COMPLETE_MARGIN_MS = 1_000L
        const val WAIT_FOR_DOWNLOAD_MS = 30_000L
        const val ASR_RETRY_DELAY_MS = 15 * 60_000L
        const val DETECTION_BATCH_CUES = 12
        const val DETECTION_MAX_DELAY_MS = 60_000L
        const val MOONSHINE_PREFIX = "moonshine"
        const val REASON_METERED = "speech model needs an unmetered network"
    }
}
