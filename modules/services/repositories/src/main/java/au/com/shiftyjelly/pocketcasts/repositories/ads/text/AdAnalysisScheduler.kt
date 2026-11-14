package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Enqueues transcript ad analysis for downloaded episodes.
 *
 * [schedule] is called when an episode finishes downloading, which is the moment its file is
 * complete. [scheduleDownloadedWithoutTranscript] catches the episodes that never go through that
 * callback: files restored after a reinstall, episodes that arrived while the analysis was off, and
 * the first launch after an upgrade.
 */
@Singleton
class AdAnalysisScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transcriber: EpisodeTranscriber,
    private val episodeManager: EpisodeManager,
    private val transcriptStore: LocalTranscriptStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun schedule(episodeUuid: String) {
        if (!FeatureFlag.isEnabled(Feature.AD_SKIPPING)) return
        if (!transcriber.isAvailable()) return
        val request = OneTimeWorkRequestBuilder<TranscriptAdAnalysisWorker>()
            .setConstraints(constraints())
            .setInputData(workDataOf(TranscriptAdAnalysisWorker.ARG_EPISODE_UUID to episodeUuid))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TranscriptAdAnalysisWorker.UNIQUE_PREFIX + episodeUuid,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Queues the analysis for downloaded episodes that have no transcript yet. Only a few episodes
     * are queued per launch, so a large library catches up over a few runs instead of starting a
     * storm of work; the work itself is unique per episode, so repeat calls are no-ops.
     */
    fun scheduleDownloadedWithoutTranscript() {
        if (!FeatureFlag.isEnabled(Feature.AD_SKIPPING)) return
        if (!transcriber.isAvailable()) return
        scope.launch(Dispatchers.IO) {
            val downloaded = runCatching { episodeManager.findDownloadedEpisodesFlow().first() }
                .onFailure { LogBuffer.e(TAG, it, "Could not list downloaded episodes for transcript analysis") }
                .getOrDefault(emptyList())
            val pending = ArrayList<String>(MAX_QUEUED_PER_LAUNCH)
            for (episode in downloaded) {
                if (pending.size >= MAX_QUEUED_PER_LAUNCH) break
                if (!transcriptStore.hasTranscript(episode.uuid)) pending += episode.uuid
            }
            if (pending.isEmpty()) return@launch
            LogBuffer.i(TAG, "Queueing transcript analysis for ${pending.size} downloaded episode(s)")
            pending.forEach(::schedule)
        }
    }

    fun cancel(episodeUuid: String) {
        WorkManager.getInstance(context).cancelUniqueWork(TranscriptAdAnalysisWorker.UNIQUE_PREFIX + episodeUuid)
    }

    private fun constraints() = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        // A stream has to be fetched, and the jev classification always needs the network.
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private companion object {
        const val TAG = "AdAnalysisScheduler"
        const val MAX_QUEUED_PER_LAUNCH = 3
    }
}
