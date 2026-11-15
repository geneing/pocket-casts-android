package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine.MoonshineStats
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background ad analysis for a downloaded episode. One episode at a time: the analyzer's mutex
 * serialises passes, and WorkManager's unique name keeps duplicate work for the same episode out.
 *
 * The first pass for a language may have to fetch the speech model. That download is the only part
 * of the pass that costs real data, so its progress is published and a metered network defers the
 * whole pass instead.
 */
@HiltWorker
class TranscriptAdAnalysisWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val analyzer: TranscriptAdAnalyzer,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val episodeUuid = inputData.getString(ARG_EPISODE_UUID) ?: return Result.failure()
        if (!analyzer.isSupported()) {
            LogBuffer.i(TAG, "Skipping transcript analysis for $episodeUuid: recognizer unavailable")
            return Result.success()
        }
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
