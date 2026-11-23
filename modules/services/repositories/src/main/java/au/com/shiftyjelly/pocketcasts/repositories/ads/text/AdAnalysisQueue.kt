package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdAnalysisDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdCueScoreDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdDetectionQueueDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdSegmentDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AsrQueueDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.UpNextDao
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState
import au.com.shiftyjelly.pocketcasts.models.to.AsrQueueItem
import au.com.shiftyjelly.pocketcasts.repositories.file.FileStorage
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The two global ad-analysis queues.
 *
 * Recognition is expensive (minutes of CPU and one resident speech model) and detection is a paid
 * remote call, so neither may run once per episode in parallel. Both are therefore durable tables
 * drained by one worker each: adding several episodes to the playlist only adds rows, and the
 * workers take them in arrival order. Progress, failures and backoffs live in the rows, so a
 * process death, a failed download or a dropped network resumes from the same place instead of
 * starting the episode again.
 */
@Singleton
class AdAnalysisQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val asrQueueDao: AsrQueueDao,
    private val detectionQueueDao: AdDetectionQueueDao,
    private val adAnalysisDao: AdAnalysisDao,
    private val adSegmentDao: AdSegmentDao,
    private val adCueScoreDao: AdCueScoreDao,
    private val upNextDao: UpNextDao,
    private val transcriber: EpisodeTranscriber,
    private val transcriptStore: LocalTranscriptStore,
    private val episodeManager: EpisodeManager,
    private val fileStorage: FileStorage,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /**
     * Queues the episode for on-device recognition. A transcript written before the queue existed
     * is registered as already recognised, so an upgrade scores it instead of recognising it again.
     */
    fun schedule(episodeUuid: String) {
        if (!isEnabled()) return
        scope.launch(Dispatchers.IO) {
            val queued = asrQueueDao.find(episodeUuid)
            if (queued != null) {
                if (queued.isComplete) {
                    // A finished transcript is only the episode's reading for the audio it was
                    // recognised from: a download that landed afterwards, or a stream stitched with
                    // different ads, has moved every cue. Recognising it again is the only repair.
                    if (transcriptUsesOtherAudio(queued)) {
                        LogBuffer.i(TAG, "Recognising $episodeUuid again: its transcript was made from other audio")
                        resetAnalysis(episodeUuid)
                        ensureAsrWorker()
                        return@launch
                    }
                    val detection = detectionQueueDao.find(episodeUuid)
                    if (detection == null || !detection.isComplete) enqueueDetectionNow(episodeUuid)
                } else {
                    // The download worker calls this the moment it has written the file, which is the
                    // only reason a pass would be parked waiting for one.
                    wakeIfAudioArrived(episodeUuid)
                    ensureAsrWorker()
                }
                return@launch
            }
            val legacyCues = transcriptStore.readCues(episodeUuid)
            if (legacyCues.isNotEmpty() && transcriptStore.isComplete(episodeUuid, transcriber.version)) {
                asrQueueDao.enqueueCompleted(episodeUuid, legacyCues.last().endMs, System.currentTimeMillis())
                enqueueDetectionNow(episodeUuid)
            } else {
                // A file without the completion marker is either a pass killed before it finished
                // or a transcript from an older pipeline; neither may be mixed with fresh cues.
                if (legacyCues.isNotEmpty()) transcriptStore.delete(episodeUuid)
                asrQueueDao.enqueue(episodeUuid, System.currentTimeMillis())
                ensureAsrWorker()
            }
        }
    }

    /** Queues detection for an episode whose transcript gained cues, or finished. */
    fun enqueueDetection(episodeUuid: String) {
        if (!isEnabled()) return
        scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            detectionQueueDao.enqueue(episodeUuid, now)
            detectionQueueDao.requestRun(episodeUuid, now + DETECTION_WAKE_DELAY_MS)
            ensureDetectionWorker()
        }
    }

    /** Adds the episode to the detection queue synchronously, for the recognition pass itself. */
    suspend fun enqueueDetectionNow(episodeUuid: String) {
        if (!isEnabled()) return
        val now = System.currentTimeMillis()
        detectionQueueDao.enqueue(episodeUuid, now)
        detectionQueueDao.requestRun(episodeUuid, now + DETECTION_WAKE_DELAY_MS)
        ensureDetectionWorker()
    }

    /**
     * Queues recognition for downloaded episodes that never went through the download callback.
     * Only a few are queued per launch, so a large library catches up over several runs instead of
     * writing thousands of rows at once; the single worker serialises whatever is queued anyway.
     */
    fun scheduleDownloadedWithoutTranscript() {
        if (!isEnabled()) return
        scope.launch(Dispatchers.IO) {
            val downloaded = runCatching { episodeManager.findDownloadedEpisodesFlow().first() }
                .onFailure { LogBuffer.e(TAG, it, "Could not list downloaded episodes for recognition") }
                .getOrDefault(emptyList())
            val pending = ArrayList<String>(MAX_QUEUED_PER_LAUNCH)
            for (episode in downloaded) {
                if (pending.size >= MAX_QUEUED_PER_LAUNCH) break
                if (!isRecognised(episode)) pending += episode.uuid
            }
            if (pending.isEmpty()) return@launch
            LogBuffer.i(TAG, "Queueing on-device recognition for ${pending.size} downloaded episode(s)")
            pending.forEach(::schedule)
        }
    }

    /**
     * Queues recognition for every podcast episode in Up Next; returns how many were queued.
     *
     * Up Next is where the next thing to play usually comes from, and an episode that arrives there
     * from another device or from a backup never passes through `UpNextQueueImpl`, so no user action
     * looks at it. Like [scheduleDownloadedWithoutTranscript] this only submits rows; the single
     * recognition worker serialises whatever is queued.
     */
    suspend fun scheduleUpNext(): Int {
        if (!isEnabled()) return 0
        val uuids = runCatching { upNextDao.findEpisodeUuidsBlocking() }
            .onFailure { LogBuffer.e(TAG, it, "Could not list the Up Next episodes for recognition") }
            .getOrDefault(emptyList())
        if (uuids.isEmpty()) return 0
        // The uuid query covers every row in Up Next, user files included, while recognition is only
        // ever queued for podcast episodes: a user file has no podcast language and no ad segments.
        val userUuids = runCatching { upNextDao.findUserEpisodesBlocking() }
            .getOrDefault(emptyList())
            .mapTo(HashSet<String>()) { it.uuid }
        val podcastUuids = uuids.filterNot { it in userUuids }
        podcastUuids.forEach(::schedule)
        LogBuffer.i(TAG, "Queueing on-device recognition for ${podcastUuids.size} Up Next episode(s)")
        return podcastUuids.size
    }

    /**
     * Re-enqueues both workers for rows a previous process left behind.
     *
     * The rows survive a process death, but the WorkManager requests do not always: a force-stop or
     * an app update cancels them, and WorkManager only re-schedules its own work on the next process
     * start. Without this, an episode queued before the app was killed sat in the table with nothing
     * to drain it — the queue was durable and still did nothing.
     */
    fun resumeInterruptedWork() {
        if (!isEnabled()) return
        scope.launch(Dispatchers.IO) {
            // Registered before the pending check: the sweep has to exist even when both queues are
            // empty, which is exactly the state of a fresh install whose Up Next was synced in.
            ensureUpNextWorker()
            val asrPending = asrQueueDao.countPending()
            val detectionPending = detectionQueueDao.countPending()
            if (asrPending == 0 && detectionPending == 0) return@launch
            LogBuffer.i(TAG, "Resuming analysis after a restart: asr=$asrPending detection=$detectionPending")
            if (asrPending > 0) {
                // A chain inherited from a previous process can be sleeping in a long backoff that
                // the current rules would no longer use; appending behind it would wait out that
                // sleep first. Nothing has started a pass yet in this process, so dropping the chain
                // costs nothing and the fresh request runs now.
                WorkManager.getInstance(context).cancelUniqueWork(TranscriptAsrWorker.UNIQUE_NAME)
                ensureAsrWorker()
            }
            if (detectionPending > 0) ensureDetectionWorker()
        }
    }

    /** Reports whether either queue still has work for the episode, for the "generating" banner. */
    fun observeIsAnalysisPending(episodeUuid: String): Flow<Boolean> {
        if (!isEnabled()) return flowOf(false)
        return combine(
            asrQueueDao.observeIsPending(episodeUuid),
            detectionQueueDao.observeIsPending(episodeUuid),
        ) { asrPending, detectionPending -> asrPending || detectionPending }
            .distinctUntilChanged()
    }

    fun cancel(episodeUuid: String) {
        scope.launch(Dispatchers.IO) {
            asrQueueDao.deleteForEpisodes(listOf(episodeUuid))
            detectionQueueDao.deleteForEpisodes(listOf(episodeUuid))
        }
    }

    suspend fun asrItem(episodeUuid: String): AsrQueueItem? = asrQueueDao.find(episodeUuid)

    suspend fun nextAsrDue(now: Long): AsrQueueItem? = asrQueueDao.findNextDue(now)

    suspend fun countAsrPending(): Int = asrQueueDao.countPending()

    /** Rows that may be recognised right now; the rest are waiting for their own retry time. */
    suspend fun countAsrDue(now: Long): Int = asrQueueDao.countDue(now)

    suspend fun nextDetectionDue(now: Long) = detectionQueueDao.findNextDue(now)

    suspend fun countDetectionPending(): Int = detectionQueueDao.countPending()

    suspend fun detectionItem(episodeUuid: String) = detectionQueueDao.find(episodeUuid)

    suspend fun analysisState(episodeUuid: String): AdAnalysisState? = adAnalysisDao.findState(episodeUuid)

    suspend fun recordAsrProgress(episodeUuid: String, processedUntilMs: Long) {
        asrQueueDao.updateProgress(episodeUuid, processedUntilMs)
    }

    suspend fun completeAsr(episodeUuid: String, processedUntilMs: Long) {
        asrQueueDao.markComplete(episodeUuid, processedUntilMs)
    }

    suspend fun failAsr(episodeUuid: String, reason: String, retryAfterMs: Long) {
        asrQueueDao.markFailure(episodeUuid, System.currentTimeMillis() + retryAfterMs, reason)
    }

    suspend fun deferAsr(episodeUuid: String, reason: String) {
        asrQueueDao.markFailure(episodeUuid, System.currentTimeMillis() + DEFERRED_RETRY_MS, reason)
    }

    /**
     * Parks a pass whose audio file does not exist yet.
     *
     * The retry is a safety net for an episode that never gets its file (a download cancelled or
     * failed): the waiting row is released by [schedule] the moment the download writes the audio,
     * so a long download costs nothing and a short one is recognised as soon as it lands.
     */
    suspend fun deferUntilAudio(episodeUuid: String) {
        asrQueueDao.markFailure(episodeUuid, System.currentTimeMillis() + WAITING_FOR_AUDIO_RETRY_MS, REASON_WAITING_FOR_AUDIO)
    }

    /**
     * Releases a row parked by [deferUntilAudio] once its audio exists.
     *
     * Only a row that is still waiting for its audio is made due, so an ordinary failure keeps its
     * own backoff, and nothing happens when [schedule] is called for an episode whose file is not
     * written yet.
     */
    private suspend fun wakeIfAudioArrived(episodeUuid: String) {
        val episode = episodeManager.findByUuid(episodeUuid) ?: return
        if (AnalysisIdentity.audioFile(episode, fileStorage) == null) return
        asrQueueDao.wakeWaitingForAudio(episodeUuid, System.currentTimeMillis(), REASON_WAITING_FOR_AUDIO)
    }

    /** Drops an episode that no longer exists from both queues. */
    suspend fun forget(episodeUuid: String) {
        asrQueueDao.deleteForEpisodes(listOf(episodeUuid))
        detectionQueueDao.deleteForEpisodes(listOf(episodeUuid))
        transcriptStore.delete(episodeUuid)
        adSegmentDao.replaceAll(episodeUuid, emptyList())
        adCueScoreDao.replaceAll(episodeUuid, emptyList())
        adAnalysisDao.delete(episodeUuid)
    }

    /** Throws away a transcript and every result derived from it, and restarts both queues. */
    suspend fun resetAnalysis(episodeUuid: String) {
        asrQueueDao.reset(episodeUuid)
        detectionQueueDao.reset(episodeUuid)
        transcriptStore.delete(episodeUuid)
        adSegmentDao.replaceAll(episodeUuid, emptyList())
        adCueScoreDao.replaceAll(episodeUuid, emptyList())
        adAnalysisDao.delete(episodeUuid)
    }

    suspend fun resetDetection(episodeUuid: String) {
        detectionQueueDao.reset(episodeUuid)
        adSegmentDao.replaceAll(episodeUuid, emptyList())
        adCueScoreDao.replaceAll(episodeUuid, emptyList())
        adAnalysisDao.delete(episodeUuid)
    }

    suspend fun recordDetectionProgress(episodeUuid: String, analyzedUntilMs: Long, isComplete: Boolean) {
        detectionQueueDao.updateProgress(episodeUuid, analyzedUntilMs, isComplete)
    }

    suspend fun failDetection(episodeUuid: String, reason: String, retryAfterMs: Long) {
        detectionQueueDao.markFailure(episodeUuid, System.currentTimeMillis() + retryAfterMs, reason)
    }

    suspend fun deferDetection(episodeUuid: String, delayMs: Long) {
        detectionQueueDao.defer(episodeUuid, System.currentTimeMillis() + delayMs)
    }

    suspend fun deleteQueueRows(episodeUuids: Collection<String>) {
        asrQueueDao.deleteForEpisodes(episodeUuids)
        detectionQueueDao.deleteForEpisodes(episodeUuids)
    }

    fun ensureAsrWorker() {
        val request = OneTimeWorkRequestBuilder<TranscriptAsrWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            // Linear and short, deliberately. This work is a single unique chain, so a retrying head
            // parks everything appended behind it: with exponential backoff at 15 minutes one episode
            // waiting for a download kept four ready ones from running for two hours. The real retry
            // schedule lives in `episode_asr_queue.next_attempt_at`, which the worker loops over, so
            // the work-level delay only has to be short enough that an added episode starts soon.
            .setBackoffCriteria(BackoffPolicy.LINEAR, ASR_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TranscriptAsrWorker.UNIQUE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    fun ensureDetectionWorker() {
        val request = OneTimeWorkRequestBuilder<AdDetectionWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            // Linear, so a failing detection cannot grow its own retry gap without bound: the row's
            // `next_attempt_at` is the real schedule, and this is only when the worker is allowed to
            // look again. It still waits for the network constraint, which is what gates a paid call.
            .setBackoffCriteria(BackoffPolicy.LINEAR, DETECTION_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            AdDetectionWorker.UNIQUE_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Registers the Up Next sweep: one run shortly after launch, then every six hours.
     *
     * An episode added to Up Next on another device, or restored from a backup, arrives without
     * passing through `UpNextQueueImpl`, so the queue is swept on a timer rather than only on a user
     * action. Six hours is rare enough to cost nothing and frequent enough that an episode synced in
     * is recognised long before it is played, when recognition is not competing with playback.
     *
     * The launch run is a separate one-shot because periodic work's first run is a whole interval
     * away, and the OS may defer it further: on a phone in a busy job-quota state the periodic
     * request sat unscheduled with no system job at all. A launch is the reliable moment to catch up.
     */
    fun ensureUpNextWorker() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()
        val workManager = WorkManager.getInstance(context)

        // One minute after launch: long enough for startup to settle, soon enough to be seen.
        workManager.enqueueUniqueWork(
            UpNextAnalysisWorker.UNIQUE_NAME + "-launch",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UpNextAnalysisWorker>()
                .setConstraints(constraints)
                .setInitialDelay(UP_NEXT_INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                .build(),
        )

        workManager.enqueueUniquePeriodicWork(
            UpNextAnalysisWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpNextAnalysisWorker>(
                repeatInterval = UP_NEXT_INTERVAL_HOURS,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = UP_NEXT_FLEX_MINUTES,
                flexTimeIntervalUnit = TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .build(),
        )
    }

    private suspend fun isRecognised(episode: BaseEpisode): Boolean {
        val item = asrQueueDao.find(episode.uuid)
        if (item != null) return item.isComplete && !transcriptUsesOtherAudio(episode, item)
        return transcriptStore.hasTranscript(episode.uuid) && transcriptStore.isComplete(episode.uuid, transcriber.version)
    }

    private suspend fun transcriptUsesOtherAudio(item: AsrQueueItem): Boolean {
        val episode = episodeManager.findByUuid(item.episodeUuid) ?: return false
        return transcriptUsesOtherAudio(episode, item)
    }

    /**
     * True when the transcript [item] finished was recognised from audio the episode no longer has.
     *
     * The transcript records the audio it read. A marker written before that record existed can only
     * be judged by timing, which is what [AnalysisIdentity.transcriptBelongsToAudio] does with the
     * row's queue time and the episode's length.
     */
    private suspend fun transcriptUsesOtherAudio(episode: BaseEpisode, item: AsrQueueItem): Boolean {
        val file = AnalysisIdentity.audioFile(episode, fileStorage) ?: return false
        val recorded = transcriptStore.recognisedAudio(episode.uuid)
        return !AnalysisIdentity.transcriptBelongsToAudio(
            recordedSignature = recorded,
            audioFile = file,
            queuedAtMs = item.enqueuedAt,
            transcriptEndMs = item.processedUntilMs,
            episodeDurationMs = episode.durationMs,
        )
    }

    private fun isEnabled() = FeatureFlag.isEnabled(Feature.AD_SKIPPING) && transcriber.isAvailable()

    private companion object {
        const val TAG = "AdAnalysisQueue"
        const val MAX_QUEUED_PER_LAUNCH = 3
        const val DETECTION_WAKE_DELAY_MS = 60_000L
        const val DEFERRED_RETRY_MS = 30 * 60_000L

        /** Marks a row that is only waiting for its audio, so the download can release it early. */
        const val REASON_WAITING_FOR_AUDIO = "waiting for the episode's audio"

        /** Safety net for a download that never writes its file; the completion normally wakes it. */
        const val WAITING_FOR_AUDIO_RETRY_MS = 30 * 60_000L

        /**
         * How long the worker chain waits after a retry. Not the retry schedule for an episode: that
         * is `episode_asr_queue.next_attempt_at`, which the worker loops over. This only bounds how
         * long an episode appended behind a retrying head has to wait, so it is minutes, not tens of
         * minutes.
         */
        const val ASR_BACKOFF_MINUTES = 1L
        const val DETECTION_BACKOFF_MINUTES = 10L

        /** Up Next sweep cadence; long, because every other trigger already covers a played episode. */
        const val UP_NEXT_INTERVAL_HOURS = 6L
        const val UP_NEXT_FLEX_MINUTES = 15L

        /** Delay before the very first sweep, so a launch does not wait a whole interval for it. */
        const val UP_NEXT_INITIAL_DELAY_MINUTES = 1L
    }
}
