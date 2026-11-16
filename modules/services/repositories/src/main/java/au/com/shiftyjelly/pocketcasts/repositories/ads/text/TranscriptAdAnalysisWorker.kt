package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import au.com.shiftyjelly.pocketcasts.repositories.R
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine.MoonshineStats
import au.com.shiftyjelly.pocketcasts.repositories.notification.NotificationHelper
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import au.com.shiftyjelly.pocketcasts.images.R as IR
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/**
 * Background ad analysis for a downloaded episode. One episode at a time: the analyzer's mutex
 * serialises passes, and WorkManager's unique name keeps duplicate work for the same episode out.
 *
 * The first pass for a language may have to fetch the speech model. That download is the only part
 * of the pass that costs real data, so its progress is published and a metered network defers the
 * whole pass instead.
 *
 * A recognition pass is minutes of CPU (roughly three times faster than real time here, so about
 * five minutes for a fifteen-minute episode), and an ordinary job is stopped by the system after
 * ten minutes. The pass therefore promotes itself to a foreground service: without that, the job
 * was cancelled mid-episode and every recognised line was thrown away, which is what
 * `JobCancellationException` in the worker's log meant.
 */
@HiltWorker
class TranscriptAdAnalysisWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val analyzer: TranscriptAdAnalyzer,
    private val notificationHelper: NotificationHelper,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val episodeUuid = inputData.getString(ARG_EPISODE_UUID) ?: return Result.failure()
        if (!analyzer.isSupported()) {
            LogBuffer.i(TAG, "Skipping transcript analysis for $episodeUuid: recognizer unavailable")
            return Result.success()
        }
        // A refusal here (the system can decline a foreground service for an app in the background)
        // is not fatal: the pass still runs, it is just exposed to the job's time limit again.
        runCatching { setForeground(foregroundInfo(episodeUuid)) }
            .onFailure { LogBuffer.w(TAG, "Could not keep the transcript pass in the foreground: ${it.message}") }

        var lastPercent = -1
        val outcome = runCatching {
            analyzer.run(episodeUuid) { progress ->
                // The download reports from blocking IO, outside the coroutine, so the async form is
                // the one that can be called here.
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
        }.onFailure { LogBuffer.e(TAG, it, "Transcript analysis crashed for $episodeUuid") }
            .getOrDefault(TranscriptAnalysis.NotAnalysed)

        LogBuffer.i(TAG, "Transcript analysis for $episodeUuid handled=${outcome.handled}")
        return when (outcome) {
            // The weights are tens of megabytes and the network is metered: WorkManager's backoff
            // re-runs this pass, and the next attempt on Wi-Fi downloads and transcribes.
            TranscriptAnalysis.NeedsUnmeteredNetwork -> Result.retry()

            TranscriptAnalysis.Analysed,
            TranscriptAnalysis.NotAnalysed,
            -> Result.success()
        }
    }

    /**
     * The notification that keeps the pass alive. It borrows the download channel because a pass is
     * the same kind of work: quiet, on the device, and worth showing while it runs.
     */
    private fun foregroundInfo(episodeUuid: String): ForegroundInfo {
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
            // One notification per episode, always positive: a hash is only used as a key here.
            episodeUuid.hashCode() and Int.MAX_VALUE,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        private const val TAG = "AdAnalysisWorker"
        const val ARG_EPISODE_UUID = "episode_uuid"
        const val ARG_MODEL_BYTES_DONE = "model_bytes_done"
        const val ARG_MODEL_BYTES_TOTAL = "model_bytes_total"
        const val ARG_MODEL_PERCENT = "model_percent"
        const val UNIQUE_PREFIX = "ad-transcript-analysis-"

        fun uniqueName(episodeUuid: String) = UNIQUE_PREFIX + episodeUuid
    }
}
