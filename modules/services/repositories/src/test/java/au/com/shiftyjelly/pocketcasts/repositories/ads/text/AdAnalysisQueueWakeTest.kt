package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.models.db.dao.AsrQueueDao
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import java.util.Date
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AdAnalysisQueueWakeTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `parking rechecks audio that arrived before the waiting state`() = runTest {
        val audio = folder.newFile("episode.mp3").apply { writeText("audio") }
        val dao = mock<AsrQueueDao>()
        val episodes = mock<EpisodeManager>()
        whenever(episodes.findByUuid("episode")).thenReturn(
            PodcastEpisode(
                uuid = "episode",
                podcastUuid = "podcast",
                publishedDate = Date(),
                downloadedFilePath = audio.absolutePath,
            ),
        )
        val queue = AdAnalysisQueue(
            context = mock(),
            asrQueueDao = dao,
            detectionQueueDao = mock(),
            adAnalysisDao = mock(),
            adSegmentDao = mock(),
            adCueScoreDao = mock(),
            upNextDao = mock(),
            transcriber = mock(),
            transcriptStore = mock(),
            episodeManager = episodes,
            fileStorage = mock(),
            writeGuard = AnalysisWriteGuard(),
            upNextQueue = mock(),
            scope = this,
        )

        queue.deferUntilAudio("episode")

        verify(dao).markFailure(eq("episode"), any(), eq("waiting for the episode's audio"))
        verify(dao).wakeWaitingForAudio(eq("episode"), any(), eq("waiting for the episode's audio"))
    }
}
