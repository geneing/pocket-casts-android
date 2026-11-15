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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Enqueues transcript ad analysis for downloaded episodes.
 *
 * [schedule] is called when an episode finishes downloading, which is the moment its file is
 * complete. [scheduleDownloadedWithoutTranscript] catches the episodes that never go through that
 * callback: files restored after a reinstall, episodes that arrived while the analysis was off, and
 * the first launch after an upgrade. The episode details screen also calls [schedule], so opening a
 * downloaded episode is enough to get its transcript even if every earlier pass was missed.
 */
@Singleton
class AdAnalysisScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val episodeManager: EpisodeManager,
    private val transcriptStore: LocalTranscriptStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun schedule(episodeUuid: String) {
        if (!isEnabled()) return
        val request = OneTimeWorkRequestBuilder<TranscriptAdAnalysisWorker>()
            .setConstraints(constraints())
            .setInputData(workDataOf(TranscriptAdAnalysisWorker.ARG_EPISODE_UUID to episodeUuid))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TranscriptAdAnalysisWorker.uniqueName(episodeUuid),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Reports whether analysis is queued or running for the episode, so a screen can show that a
     * transcript is being generated instead of looking like there is none.
     */
    fun observeIsAnalysisPending(episodeUuid: String): Flow<Boolean> {
        if (!isEnabled()) return flowOf(false)
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(TranscriptAdAnalysisWorker.uniqueName(episodeUuid))
            .map { infos -> infos.any { info -> !info.state.isFinished } }
    }

    /**
     * Queues the analysis for downloaded episodes that have no transcript yet. Only a few episodes
     * are queued per launch, so a large library catches up over a few runs instead of starting a
     * storm of work; the work itself is unique per episode, so repeat calls are no-ops.
     */
    fun scheduleDownloadedWithoutTranscript() {
        if (!isEnabled()) return
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
        WorkManager.getInstance(context).cancelUniqueWork(TranscriptAdAnalysisWorker.uniqueName(episodeUuid))
    }

    private fun isEnabled() = FeatureFlag.isEnabled(Feature.AD_SKIPPING)

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
