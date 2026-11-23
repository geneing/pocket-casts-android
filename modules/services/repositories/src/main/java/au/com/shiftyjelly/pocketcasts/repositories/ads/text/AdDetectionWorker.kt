package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Drains the global ad-detection queue one episode at a time.
 *
 * Detection is the only step that needs the network and costs money per request, so it is a single
 * worker rather than one per episode. The queue row holds the last scored cue and the next allowed
 * attempt: a dropped connection, an API error or a killed process resumes at the same cue, and a
 * later recognition chunk wakes the episode again without re-scoring what was already paid for.
 */
@HiltWorker
class AdDetectionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: AdAnalysisQueue,
    private val analyzer: TranscriptAdAnalyzer,
    private val writeGuard: AnalysisWriteGuard,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!FeatureFlag.isEnabled(Feature.AD_SKIPPING)) return Result.success()
        return drainQueue()
    }

    internal suspend fun drainQueue(): Result {
        val startedAt = SystemClock.elapsedRealtime()
        var sawRetry = false
        while (true) {
            val item = queue.nextDetectionDue(System.currentTimeMillis()) ?: break
            val generation = writeGuard.generation(item.episodeUuid)
            val outcome = try {
                analyzer.run(item.episodeUuid)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LogBuffer.e(TAG, error, "Ad detection crashed for ${item.episodeUuid}")
                writeGuard.ifCurrent(item.episodeUuid, generation) {
                    queue.failDetection(item.episodeUuid, "crashed: ${error.javaClass.simpleName}", CRASH_RETRY_MS)
                    DetectionOutcome.Retry
                } ?: DetectionOutcome.NotQueued
            }
            LogBuffer.i(TAG, "Ad detection for ${item.episodeUuid}: $outcome")
            if (outcome is DetectionOutcome.Retry) sawRetry = true
            if (SystemClock.elapsedRealtime() - startedAt >= PASS_BUDGET_MS) return Result.retry()
        }

        return if (sawRetry || queue.countDetectionPending() > 0) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "AdDetectionWorker"
        const val UNIQUE_NAME = "ad-detection-queue"

        /** Short pass: one call per window, and the whole queue must yield to user-visible work. */
        private const val PASS_BUDGET_MS = 5 * 60_000L
        private const val CRASH_RETRY_MS = 10 * 60_000L
    }
}
