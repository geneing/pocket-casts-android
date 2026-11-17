package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Sweeps Up Next for podcast episodes that nothing has queued yet.
 *
 * An episode added to Up Next on another device, or restored from a backup, lands in the table
 * without passing through `UpNextQueueImpl`, so no user action ever schedules its recognition. This
 * periodic sweep is the trigger that does not depend on the user touching the episode; every other
 * start path (download, details, playback, a local Up Next mutation) already queues it, and the queue
 * is idempotent, so a sweep over episodes that are already done costs one row lookup each.
 */
@HiltWorker
class UpNextAnalysisWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: AdAnalysisQueue,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!FeatureFlag.isEnabled(Feature.AD_SKIPPING)) {
            LogBuffer.i(TAG, "Skipping the Up Next sweep: ad skipping is off")
            return Result.success()
        }
        val queued = queue.scheduleUpNext()
        LogBuffer.i(TAG, "Up Next sweep queued recognition for $queued episode(s)")
        return Result.success()
    }

    companion object {
        private const val TAG = "UpNextAnalysis"
        const val UNIQUE_NAME = "up-next-analysis"
    }
}
