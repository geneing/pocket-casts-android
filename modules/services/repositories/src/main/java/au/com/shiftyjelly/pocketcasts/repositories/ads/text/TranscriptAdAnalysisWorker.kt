package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background ad analysis for a downloaded episode. One episode at a time: the analyzer's mutex
 * serialises passes, and WorkManager's unique name keeps duplicate work for the same episode out.
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
        val handled = runCatching { analyzer.run(episodeUuid) }
            .onFailure { LogBuffer.e(TAG, it, "Transcript analysis crashed for $episodeUuid") }
            .getOrDefault(false)
        LogBuffer.i(TAG, "Transcript analysis for $episodeUuid handled=$handled")
        return Result.success()
    }

    companion object {
        private const val TAG = "AdAnalysisWorker"
        const val ARG_EPISODE_UUID = "episode_uuid"
        const val UNIQUE_PREFIX = "ad-transcript-analysis-"
    }
}
