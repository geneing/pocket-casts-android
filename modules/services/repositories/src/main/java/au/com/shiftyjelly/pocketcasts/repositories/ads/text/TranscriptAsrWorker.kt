package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import au.com.shiftyjelly.pocketcasts.repositories.R
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine.MoonshineStats
import au.com.shiftyjelly.pocketcasts.repositories.notification.NotificationHelper
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import au.com.shiftyjelly.pocketcasts.images.R as IR
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/**
 * Drains the global recognition queue, one episode at a time.
 *
 * One worker for the whole app is what keeps several playlist episodes from starting several
 * recognizer passes at once. Each item is resumed from the cue it reached last, so a cancellation
 * or a process death costs the line in flight rather than the pass. An incomplete queue makes the
 * worker retry with WorkManager's backoff, which persists across restarts; a pass that ended early
 * has already written its progress.
 */
@HiltWorker
class TranscriptAsrWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: AdAnalysisQueue,
    private val generator: OnDeviceTranscriptGenerator,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!generator.isSupported() || !FeatureFlag.isEnabled(Feature.AD_SKIPPING)) {
            LogBuffer.i(TAG, "Skipping recognition: the engine is unavailable or ad skipping is off")
            return Result.success()
        }
        runCatching { setForeground(foregroundInfo()) }
            .onFailure { LogBuffer.w(TAG, "Could not keep the recognition pass in the foreground: ${it.message}") }

        val startedAt = SystemClock.elapsedRealtime()
        var lastPercent = -1
        while (true) {
            val item = queue.nextAsrDue(System.currentTimeMillis()) ?: break
            val outcome = runCatching {
                generator.run(item.episodeUuid) { progress ->
                    val percent = MoonshineStats.percent(progress.bytesDone, progress.bytesTotal)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        setProgressAsync(
                            workDataOf(
                                ARG_MODEL_BYTES_DONE to progress.bytesDone,
                                ARG_MODEL_BYTES_TOTAL to progress.bytesTotal,
                                ARG_MODEL_PERCENT to percent,
                            ),
                        )
                    }
                }
            }.onFailure { LogBuffer.e(TAG, it, "Recognition crashed for ${item.episodeUuid}") }
                .getOrDefault(AsrOutcome.Retry)

            LogBuffer.i(TAG, "Recognition for ${item.episodeUuid}: $outcome")
            if (SystemClock.elapsedRealtime() - startedAt >= PASS_BUDGET_MS) {
                LogBuffer.i(TAG, "Recognition pass budget expired, continuing with the next worker run")
                return Result.retry()
            }
        }

        // A row whose backoff has not expired yet is still work: returning retry keeps the queue
        // alive through a metered network or a failed download without another UI event. The split
        // is logged because "why is the worker still retrying" is otherwise unanswerable from a
        // report: `due=0` means nothing could run and the next attempt is the row's own retry time.
        val pending = queue.countAsrPending()
        val due = queue.countAsrDue(System.currentTimeMillis())
        LogBuffer.i(TAG, "Recognition queue: pending=$pending due=$due")
        return if (pending > 0) Result.retry() else Result.success()
    }

    /** The notification that keeps a recognition pass alive past the job time limit. */
    private fun foregroundInfo(): ForegroundInfo {
        val notification = notificationHelper.downloadChannelBuilder()
            .setSmallIcon(IR.drawable.notification)
            .setColor(ContextCompat.getColor(applicationContext, R.color.notification_color))
            .setContentTitle(applicationContext.getString(LR.string.transcript_generating_notification_title))
            .setContentText(applicationContext.getString(LR.string.transcript_generating_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        private const val TAG = "AsrWorker"
        const val UNIQUE_NAME = "ad-asr-queue"
        const val ARG_MODEL_BYTES_DONE = "model_bytes_done"
        const val ARG_MODEL_BYTES_TOTAL = "model_bytes_total"
        const val ARG_MODEL_PERCENT = "model_percent"

        /** One worker run may recognise for this long before WorkManager is asked to run it again. */
        private const val PASS_BUDGET_MS = 30 * 60_000L
        private const val NOTIFICATION_ID = 0x5A5
    }
}
