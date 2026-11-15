package au.com.shiftyjelly.pocketcasts.repositories.ads

import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdAnalysisDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdCueScoreDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdSegmentDao
import au.com.shiftyjelly.pocketcasts.models.to.AdCueScore
import au.com.shiftyjelly.pocketcasts.models.to.AdSegment
import au.com.shiftyjelly.pocketcasts.models.to.DbAdCueScore
import au.com.shiftyjelly.pocketcasts.models.to.DbAdSegment
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptAdAnalyzer
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptAnalysis
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
class AdSegmentManagerImpl @Inject constructor(
    private val adSegmentDao: AdSegmentDao,
    private val adAnalysisDao: AdAnalysisDao,
    private val adCueScoreDao: AdCueScoreDao,
    private val audioAdAnalyzer: Lazy<AudioAdAnalyzer>,
    private val transcriptAdAnalyzer: Lazy<TranscriptAdAnalyzer>,
    @ApplicationScope private val scope: CoroutineScope,
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
     * Downloaded episodes first get an on-device transcript, which the remote classifier will score
     * later; until then the transcript analyser never claims the episode, so the audio analyser
     * produces the segments. Streams skip transcription and use the audio path, which follows the
     * player cache.
     */
    override fun analyze(episodeUuid: String) {
        scope.launch(Dispatchers.IO) {
            val handled = runCatching { transcriptAdAnalyzer.get().run(episodeUuid) }
                .getOrDefault(TranscriptAnalysis.NotAnalysed)
                .handled
            if (!handled) {
                audioAdAnalyzer.get().analyze(episodeUuid)
            }
        }
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
