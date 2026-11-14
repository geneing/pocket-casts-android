package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Enqueues transcript ad analysis after an episode finishes downloading. */
@Singleton
class AdAnalysisScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transcriber: EpisodeTranscriber,
) {
    fun schedule(episodeUuid: String) {
        if (!FeatureFlag.isEnabled(Feature.AD_SKIPPING)) return
        if (!transcriber.isAvailable()) return
        val request = OneTimeWorkRequestBuilder<TranscriptAdAnalysisWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInputData(workDataOf(TranscriptAdAnalysisWorker.ARG_EPISODE_UUID to episodeUuid))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TranscriptAdAnalysisWorker.UNIQUE_PREFIX + episodeUuid,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(episodeUuid: String) {
        WorkManager.getInstance(context).cancelUniqueWork(TranscriptAdAnalysisWorker.UNIQUE_PREFIX + episodeUuid)
    }
}
