package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Periodically removes locally generated transcripts that have not been used for 30 days. */
@HiltWorker
class TranscriptRetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: AdAnalysisQueue,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val deleted = queue.expireIdleTranscripts(System.currentTimeMillis())
        LogBuffer.i(TAG, "Removed $deleted idle transcript(s)")
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        LogBuffer.e(TAG, error, "Could not clean up idle transcripts")
        Result.retry()
    }

    companion object {
        private const val TAG = "TranscriptRetention"
        private const val UNIQUE_NAME = "transcript-retention"

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniqueWork(
                "$UNIQUE_NAME-launch",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TranscriptRetentionWorker>()
                    .setInitialDelay(1, TimeUnit.MINUTES)
                    .build(),
            )
            manager.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TranscriptRetentionWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
