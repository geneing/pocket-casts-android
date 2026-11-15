package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdAnalysisDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdSegmentDao
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState
import au.com.shiftyjelly.pocketcasts.models.to.AdSegment
import au.com.shiftyjelly.pocketcasts.models.to.AdSegmentKind
import au.com.shiftyjelly.pocketcasts.models.to.AdSegmentOrigin
import au.com.shiftyjelly.pocketcasts.models.to.DbAdSegment
import au.com.shiftyjelly.pocketcasts.models.to.Transcript
import au.com.shiftyjelly.pocketcasts.models.to.TranscriptEntry
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevAdClassifier
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevAdModelStore
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevClassification
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevPrompt
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.buildPods
import au.com.shiftyjelly.pocketcasts.repositories.file.FileStorage
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.transcript.TranscriptManager
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Ad analysis from a transcript, scored by the remote jev choice classifier.
 *
 * The episode's existing transcript is used when the app has one, whether it is the show's own
 * transcript or the generated VTT the transcript tab shows. Those cues already carry word-level
 * timings, so this is both cheaper and more accurate than recognising the audio locally. Only an
 * episode without any transcript is transcribed on device, and that transcript is then cached by
 * [LocalTranscriptStore] and registered with the transcript stack.
 *
 * The analysis is reused while the audio file (or stream URL), the transcript identity, the
 * recognizer version and the jev model (including its prompt revision) are unchanged. Pods are
 * written as [DbAdSegment]s with [AdSegmentOrigin.Transcript].
 *
 * Confidence is the pod's mean p(advertisement), so a pod the classifier is sure about clears
 * [AdSegment.MIN_AUTO_SKIP_CONFIDENCE] and is skipped automatically, while a weak one stays a
 * suggestion.
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
    private val transcriptManager: TranscriptManager,
    private val generatedTranscriptLoader: GeneratedTranscriptLoader,
    private val jevAdModelStore: JevAdModelStore,
    private val fileStorage: FileStorage,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var analysisJob: Job? = null

    /**
     * When an episode's last analysis attempt failed. Streams are fetched whole before they can be
     * transcribed, so retrying on every playback tick would download the episode again; the window
     * keeps a failure from turning into a download loop while still allowing a later attempt.
     */
    private val failedAttempts = ConcurrentHashMap<String, Long>()

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
        val failedAt = failedAttempts[episodeUuid]
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_FAILURE_MS) {
            Timber.tag(TAG).d("Episode %s failed to analyse recently, not retrying yet", episodeUuid)
            return false
        }
        val startedAt = System.currentTimeMillis()
        val episode = episodeManager.findByUuid(episodeUuid) ?: return false
        val source = resolveSource(episode)
        val model = jevAdModelStore.selectedJevAdModel()
        val state = adAnalysisDao.findState(episodeUuid)
        val sourceSignature = source?.let(AnalysisIdentity::audioSignature)

        // A repeat play of an episode already scored from its generated transcript needs no fetch:
        // the transcript URL and the audio are both known before anything is loaded.
        val generatedUrl = generatedTranscriptLoader.urlFor(episode)
        if (sourceSignature != null) {
            val analysed = listOfNotNull(generatedUrl?.let(::transcriptAnalysisVersion), transcriber.version)
                .any { version ->
                    AnalysisIdentity.isReusableTranscript(state, sourceSignature, version, model, JevPrompt.VERSION)
                }
            if (analysed) {
                Timber.tag(TAG).d("Episode %s is already analysed with its transcript, model and prompt", episodeUuid)
                return true
            }
        }

        val existing = loadExistingTranscript(episodeUuid)
        if (source == null && existing == null) {
            Timber.tag(TAG).d("Episode %s has neither audio nor a transcript", episodeUuid)
            return false
        }

        // A transcript the app already has is the analysis input; otherwise the recognizer is.
        // Keeping the version here makes a newly available transcript invalidate an older,
        // speech-recognised analysis of the same episode.
        val signature = sourceSignature ?: existing!!.url
        val sttVersion = existing?.analysisVersion(transcriber.version) ?: transcriber.version
        if (AnalysisIdentity.isReusableTranscript(state, signature, sttVersion, model, JevPrompt.VERSION)) {
            Timber.tag(TAG).d("Episode %s is already analysed with this transcript, model and prompt", episodeUuid)
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

        val cues = if (existing != null) {
            existing.toAnalysisCues()
        } else {
            if (source == null || !transcriber.isAvailable()) return false
            val result = transcriber.transcribe(episodeUuid, source, episode.durationMs.toLong())
            when (result) {
                is TranscriptResult.Success -> result.cues

                is TranscriptResult.Unavailable -> {
                    LogBuffer.i(TAG, "On-device transcription unavailable: ${result.reason}")
                    return false
                }

                is TranscriptResult.Failed -> {
                    LogBuffer.e(TAG, "Transcription failed for $episodeUuid: ${result.message}")
                    noteFailure(episodeUuid)
                    return false
                }
            }
        }
        if (cues.isEmpty()) {
            LogBuffer.i(TAG, "No transcript cues to score for $episodeUuid")
            noteFailure(episodeUuid)
            return false
        }

        if (existing == null) {
            transcriptStore.save(episodeUuid, cues)
            LogBuffer.i(
                TAG,
                "Cached transcript for $episodeUuid: ${cues.size} cues, " +
                    "${System.currentTimeMillis() - startedAt}ms",
            )
        } else {
            LogBuffer.i(TAG, "Using the existing transcript for $episodeUuid: ${cues.size} cues from ${existing.url}")
        }

        val podcastTitle = podcastManager.findPodcastByUuid(episode.podcastUuid)?.title.orEmpty()
        val classification = classifier.classify(cues, podcastTitle.ifBlank { UNKNOWN_SHOW }, model)
        val scored = when (classification) {
            is JevClassification.Unavailable -> {
                LogBuffer.i(TAG, "jev classification unavailable for $episodeUuid: ${classification.reason}")
                noteFailure(episodeUuid)
                return false
            }

            is JevClassification.Failed -> {
                LogBuffer.e(TAG, "jev classification failed for $episodeUuid: ${classification.reason}")
                noteFailure(episodeUuid)
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
                    confidence = pod.meanAdvertisementProbability.coerceIn(0f, 1f),
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
        failedAttempts.remove(episodeUuid)
        return true
    }

    private fun noteFailure(episodeUuid: String) {
        failedAttempts[episodeUuid] = System.currentTimeMillis()
    }

    /**
     * The transcript the app can already show, if there is one. Availability is checked first
     * because loading an episode that has no transcript waits for show notes to be processed.
     * When the transcript stack has not recorded the generated transcript yet, it is fetched from
     * the URL the show notes advertise so analysis does not wait for a transcript screen.
     */
    private suspend fun loadExistingTranscript(episodeUuid: String): Transcript.Text? {
        if (transcriptManager.observeIsTranscriptAvailable(episodeUuid).first()) {
            val loaded = transcriptManager.loadTranscript(episodeUuid) as? Transcript.Text
            if (!loaded?.entries.isNullOrEmpty()) return loaded
        }
        return generatedTranscriptLoader.load(episodeUuid)?.takeIf { it.entries.isNotEmpty() }
    }

    /**
     * Where to transcribe from, preferring the downloaded file.
     *
     * The download worker asks for the analysis as soon as the file is written, before the episode
     * row records its path, so the recorded path is often still empty at this point. The download
     * location is deterministic from the episode uuid, and a downloaded episode whose file is not
     * resolvable yet is given a bounded wait for the row to catch up. An episode that is not
     * downloaded at all is transcribed straight from its stream.
     */
    private suspend fun resolveSource(episode: BaseEpisode): TranscriptSource? {
        resolveAudioFile(episode)?.let { return TranscriptSource.Downloaded(it) }
        if (episode.isDownloaded) {
            awaitAudioFile(episode.uuid)?.let { return TranscriptSource.Downloaded(it) }
        }
        return episode.streamUrl
            ?.takeIf { it.isNotBlank() && !episode.isStreamUrlHls }
            ?.let(TranscriptSource::Stream)
    }

    private fun resolveAudioFile(episode: BaseEpisode): File? {
        val recorded = episode.downloadedFilePath?.let(::File)?.takeIf { it.isFile }
        if (recorded != null) return recorded
        return fileStorage.getOrCreatePodcastEpisodeFile(episode)?.takeIf { it.isFile && it.length() > 0L }
    }

    /**
     * Waits for the download row to point at the file before giving up, so a download that has just
     * finished is never skipped. The wait is bounded so a cancelled or failed download does not
     * hold the analysis.
     */
    private suspend fun awaitAudioFile(episodeUuid: String): File? = withTimeoutOrNull(WAIT_FOR_DOWNLOAD_MS) {
        episodeManager.findEpisodeByUuidFlow(episodeUuid)
            .mapNotNull { resolveAudioFile(it) }
            .firstOrNull()
    }

    companion object {
        private const val TAG = "TranscriptAdAnalyzer"
        private const val MIN_POD_MS = 8_000L
        private const val UNKNOWN_SHOW = "Unknown podcast"

        /** How long the analysis waits for a download to be recorded before giving up. */
        private const val WAIT_FOR_DOWNLOAD_MS = 30_000L

        /** How long a failed attempt blocks a retry, so a stream is not fetched again immediately. */
        private const val RETRY_AFTER_FAILURE_MS = 10 * 60_000L
    }
}

/** The transcript's timed text entries as classifier cues, in transcript order. */
internal fun Transcript.Text.toAnalysisCues(): List<TranscriptCue> = entries.mapNotNull { entry ->
    val text = entry as? TranscriptEntry.Text ?: return@mapNotNull null
    val value = text.value.trim()
    if (value.isEmpty() || text.startTimeMs < 0) return@mapNotNull null
    TranscriptCue(
        startMs = text.startTimeMs,
        endMs = text.endTimeMs.coerceAtLeast(text.startTimeMs),
        text = value,
    )
}

/**
 * Identifies what produced the cues. A transcript generated on device is tied to the recognizer,
 * a transcript the app fetched is tied to its URL, so either changing starts a fresh analysis.
 */
internal fun Transcript.Text.analysisVersion(recognizerVersion: String): String = if (url.startsWith(LocalTranscriptStore.SCHEME)) recognizerVersion else transcriptAnalysisVersion(url)

/** The analysis identity of a transcript the app fetched by URL. */
internal fun transcriptAnalysisVersion(url: String): String = "transcript:$url"
