package au.com.shiftyjelly.pocketcasts.repositories.ads

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdAnalysisDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdCueScoreDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdSegmentDao
import au.com.shiftyjelly.pocketcasts.models.to.AdCueScore
import au.com.shiftyjelly.pocketcasts.models.to.AdSegment
import au.com.shiftyjelly.pocketcasts.models.to.DbAdCueScore
import au.com.shiftyjelly.pocketcasts.models.to.DbAdSegment
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.AdAnalysisQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class AdSegmentManagerImpl @Inject constructor(
    private val adSegmentDao: AdSegmentDao,
    private val adAnalysisDao: AdAnalysisDao,
    private val adCueScoreDao: AdCueScoreDao,
    private val queue: AdAnalysisQueue,
) : AdSegmentManager {
    override fun observeSegments(episodeUuid: String): Flow<List<AdSegment>> {
        return adSegmentDao.observeSegmentsForEpisode(episodeUuid)
            .map { segments -> segments.map(DbAdSegment::toAdSegment) }
            .distinctUntilChanged()
    }

    override fun observeCueScores(episodeUuid: String): Flow<List<AdCueScore>> {
        return adCueScoreDao.observeScoresForEpisode(episodeUuid)
            .map { scores -> scores.map(DbAdCueScore::toAdCueScore) }
            .distinctUntilChanged()
    }

    /**
     * Queues the episode for recognition and detection. The queues are global and durable, so a
     * playlist of several episodes never starts several passes, and a screen asking for the analysis
     * only adds a row instead of running minutes of CPU in the caller's coroutine.
     */
    override fun analyze(episodeUuid: String) {
        queue.schedule(episodeUuid)
    }

    override suspend fun hasSegments(episodeUuid: String) = adSegmentDao.countForEpisode(episodeUuid) > 0

    override suspend fun setDeselected(episodeUuid: String, segmentIndex: Int, deselected: Boolean) {
        adSegmentDao.setDeselected(episodeUuid, segmentIndex, deselected)
    }

    override suspend fun clear(episodeUuid: String) {
        adSegmentDao.replaceAll(episodeUuid, emptyList())
        adCueScoreDao.replaceAll(episodeUuid, emptyList())
        adAnalysisDao.delete(episodeUuid)
    }
}

private fun DbAdCueScore.toAdCueScore() = AdCueScore(
    cueIndex = cueIndex,
    startTimeMs = startTimeMs,
    endTimeMs = endTimeMs,
    probability = probability,
)

private fun DbAdSegment.toAdSegment() = AdSegment(
    index = index,
    startTime = startTimeMs.milliseconds,
    endTime = endTimeMs.milliseconds,
    kind = kind,
    origin = origin,
    confidence = confidence,
    title = title,
    selected = !deselected,
    // On-device analysis timestamps are already in the timeline of the analysed audio.
    isAligned = true,
)
