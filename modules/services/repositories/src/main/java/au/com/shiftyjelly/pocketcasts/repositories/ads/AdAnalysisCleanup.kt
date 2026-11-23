package au.com.shiftyjelly.pocketcasts.repositories.ads

import au.com.shiftyjelly.pocketcasts.models.db.dao.AdAnalysisDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdCueScoreDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdDetectionQueueDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdSegmentDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AsrQueueDao
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.AnalysisWriteGuard
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.LocalTranscriptStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes what the ad analysis stores for an episode: its ad segments and the analysis state.
 *
 * The ad segments describe one audio file, so they go away with the download they were found in.
 * An on-device transcript does not: it stays readable after the download is deleted, and only a
 * full [deleteForEpisodes] removes the file and its row when the episode itself goes away.
 */
@Singleton
class AdAnalysisCleanup @Inject constructor(
    private val transcriptStore: LocalTranscriptStore,
    private val adSegmentDao: AdSegmentDao,
    private val adCueScoreDao: AdCueScoreDao,
    private val adAnalysisDao: AdAnalysisDao,
    private val asrQueueDao: AsrQueueDao,
    private val detectionQueueDao: AdDetectionQueueDao,
    private val writeGuard: AnalysisWriteGuard,
) {
    /** Clears the ad analysis but keeps the on-device transcript. */
    suspend fun clearAnalysisForEpisodes(episodeUuids: Collection<String>) = writeGuard.invalidate(episodeUuids) {
        asrQueueDao.deleteForEpisodes(episodeUuids)
        detectionQueueDao.deleteForEpisodes(episodeUuids)
        episodeUuids.forEach { episodeUuid ->
            adSegmentDao.replaceAll(episodeUuid, emptyList())
            adCueScoreDao.replaceAll(episodeUuid, emptyList())
            adAnalysisDao.delete(episodeUuid)
        }
    }

    /** Removes everything the ad analysis stores for episodes that are going away. */
    suspend fun deleteForEpisodes(episodeUuids: Collection<String>) = writeGuard.invalidate(episodeUuids) {
        asrQueueDao.deleteForEpisodes(episodeUuids)
        detectionQueueDao.deleteForEpisodes(episodeUuids)
        episodeUuids.forEach { episodeUuid ->
            transcriptStore.delete(episodeUuid)
            adSegmentDao.replaceAll(episodeUuid, emptyList())
            adCueScoreDao.replaceAll(episodeUuid, emptyList())
            adAnalysisDao.delete(episodeUuid)
        }
    }
}
