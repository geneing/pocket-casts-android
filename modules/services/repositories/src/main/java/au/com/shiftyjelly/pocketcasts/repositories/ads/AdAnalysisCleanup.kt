package au.com.shiftyjelly.pocketcasts.repositories.ads

import au.com.shiftyjelly.pocketcasts.models.db.dao.AdAnalysisDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdSegmentDao
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.LocalTranscriptStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes everything the ad analysis stores for episodes that are going away: the generated
 * transcript file and its row, the detected ad segments and the analysis state.
 *
 * Nothing cascades from `podcast_episodes`, so an episode that is deleted from the library would
 * otherwise leave a WebVTT file in app storage and orphaned rows keyed by its uuid.
 */
@Singleton
class AdAnalysisCleanup @Inject constructor(
    private val transcriptStore: LocalTranscriptStore,
    private val adSegmentDao: AdSegmentDao,
    private val adAnalysisDao: AdAnalysisDao,
) {
    suspend fun deleteForEpisodes(episodeUuids: Collection<String>) {
        episodeUuids.forEach { episodeUuid ->
            transcriptStore.delete(episodeUuid)
            adSegmentDao.replaceAll(episodeUuid, emptyList())
            adAnalysisDao.delete(episodeUuid)
        }
    }
}
