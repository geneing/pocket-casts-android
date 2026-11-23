package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdAnalysisDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdCueScoreDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdDetectionQueueDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AdSegmentDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.AsrQueueDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.TranscriptDao
import au.com.shiftyjelly.pocketcasts.models.db.dao.UpNextDao
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.to.AdDetectionQueueItem
import au.com.shiftyjelly.pocketcasts.models.to.AsrQueueItem
import au.com.shiftyjelly.pocketcasts.models.type.EpisodeDownloadStatus
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import dagger.Lazy
import java.io.IOException
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TranscriptRetentionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val asr: AsrQueueDao = mock()
    private val detection: AdDetectionQueueDao = mock()
    private val analysis: AdAnalysisDao = mock()
    private val segments: AdSegmentDao = mock()
    private val scores: AdCueScoreDao = mock()
    private val transcripts: TranscriptDao = mock()
    private val episodes: EpisodeManager = mock()
    private val upNextDao: UpNextDao = mock()
    private val upNext: UpNextQueue = mock()
    private val store = LocalTranscriptStore(context, transcripts)
    private val episode = PodcastEpisode(uuid = EPISODE, podcastUuid = "podcast", publishedDate = Date())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val queue = AdAnalysisQueue(
        context = context,
        asrQueueDao = asr,
        detectionQueueDao = detection,
        adAnalysisDao = analysis,
        adSegmentDao = segments,
        adCueScoreDao = scores,
        upNextDao = upNextDao,
        transcriber = mock(),
        transcriptStore = store,
        episodeManager = episodes,
        fileStorage = mock(),
        writeGuard = AnalysisWriteGuard(),
        upNextQueue = Lazy { upNext },
        scope = scope,
    )

    @Before
    fun setUp() = runBlocking {
        store.delete(EPISODE)
        store.delete(OTHER_EPISODE)
        whenever(episodes.findByUuid(EPISODE)).thenReturn(episode)
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        store.delete(EPISODE)
        store.delete(OTHER_EPISODE)
    }

    @Test
    fun `an idle transcript and its derived data are removed after thirty days`() = runTest {
        val now = System.currentTimeMillis()
        store.save(EPISODE, listOf(TranscriptCue(0, 1_000, "hello")))
        store.recordAudio(EPISODE, "audio")
        store.markComplete(EPISODE, "engine")
        val temporary = store.file(EPISODE).resolveSibling("$EPISODE.vtt.tmp").apply { writeText("partial") }
        assertTrue(store.file(EPISODE).setLastModified(now - 31 * DAY_MS))

        assertEquals(1, queue.expireIdleTranscripts(now))

        assertFalse(store.hasTranscript(EPISODE))
        assertFalse(temporary.exists())
        assertFalse(store.isComplete(EPISODE, "engine"))
        assertEquals(null, store.recognisedAudio(EPISODE))
        verify(asr).markExpired(EPISODE, "transcript expired")
        verify(detection).deleteForEpisodes(listOf(EPISODE))
        verify(segments).replaceAll(EPISODE, emptyList())
        verify(scores).replaceAll(EPISODE, emptyList())
        verify(analysis).delete(EPISODE)
    }

    @Test
    fun `a transcript used recently is retained even if it was previously old`() = runTest {
        val now = System.currentTimeMillis()
        store.save(EPISODE, listOf(TranscriptCue(0, 1_000, "hello")))
        assertTrue(store.file(EPISODE).setLastModified(now - 31 * DAY_MS))
        assertTrue(store.readForDisplay(EPISODE)?.contains("hello") == true)

        assertEquals(0, queue.expireIdleTranscripts(now))
        assertTrue(store.hasTranscript(EPISODE))
    }

    @Test
    fun `a transcript reopened after the sweep started is retained`() = runTest {
        val now = System.currentTimeMillis()
        oldTranscript(now - 31 * DAY_MS)
        val cutoff = now - 30 * DAY_MS
        assertTrue(EPISODE in store.idleTranscriptUuids(cutoff))
        store.readForDisplay(EPISODE)

        assertFalse(queue.expireIdleTranscript(EPISODE, cutoff, now))
        assertTrue(store.hasTranscript(EPISODE))
        verify(asr, never()).markExpired(any(), any())
    }

    @Test
    fun `a transcript younger than thirty days is retained`() = runTest {
        val now = System.currentTimeMillis()
        oldTranscript(now - 29 * DAY_MS)
        assertEquals(0, queue.expireIdleTranscripts(now))
        assertTrue(store.hasTranscript(EPISODE))
    }

    @Test
    fun `a transcript expires at the thirty day boundary`() = runTest {
        val now = System.currentTimeMillis()
        oldTranscript(now - 30 * DAY_MS)

        assertEquals(1, queue.expireIdleTranscripts(now))
        assertFalse(store.hasTranscript(EPISODE))
    }

    @Test
    fun `an orphan transcript is deleted without leaving a queue marker`() = runTest {
        val now = System.currentTimeMillis()
        oldTranscript(now - 31 * DAY_MS)
        whenever(episodes.findByUuid(EPISODE)).thenReturn(null)

        assertEquals(1, queue.expireIdleTranscripts(now))
        verify(asr).deleteForEpisodes(listOf(EPISODE))
        verify(asr, never()).markExpired(any(), any())
    }

    @Test
    fun `a failing transcript does not prevent other old transcripts being cleaned up`() = runTest {
        val now = System.currentTimeMillis()
        oldTranscript(now - 31 * DAY_MS)
        store.save(OTHER_EPISODE, listOf(TranscriptCue(0, 1_000, "other")))
        assertTrue(store.file(OTHER_EPISODE).setLastModified(now - 31 * DAY_MS))
        whenever(episodes.findByUuid(EPISODE)).doSuspendableAnswer { throw IOException("database error") }
        whenever(episodes.findByUuid(OTHER_EPISODE)).thenReturn(episode.copy(uuid = OTHER_EPISODE))

        try {
            queue.expireIdleTranscripts(now)
            fail("Expected cleanup to report the failure")
        } catch (_: IOException) {
            assertTrue(store.hasTranscript(EPISODE))
            assertFalse(store.hasTranscript(OTHER_EPISODE))
        }
    }

    @Test
    fun `up next episodes are never expired`() = runTest {
        val now = System.currentTimeMillis()
        oldTranscript(now - 60 * DAY_MS)
        whenever(upNextDao.countByEpisodeBlocking(EPISODE)).thenReturn(1)

        assertEquals(0, queue.expireIdleTranscripts(now))
        assertTrue(store.hasTranscript(EPISODE))
        verify(asr, never()).markExpired(any(), any())
    }

    @Test
    fun `the current episode is retained even when it is not in the up next table`() = runTest {
        val now = System.currentTimeMillis()
        oldTranscript(now - 60 * DAY_MS)
        whenever(upNext.currentEpisode).thenReturn(episode)

        assertEquals(0, queue.expireIdleTranscripts(now))
        assertTrue(store.hasTranscript(EPISODE))
    }

    @Test
    fun `the live up next queue is retained even before its table is updated`() = runTest {
        val now = System.currentTimeMillis()
        oldTranscript(now - 60 * DAY_MS)
        whenever(upNext.queueEpisodes).thenReturn(listOf(episode))

        assertEquals(0, queue.expireIdleTranscripts(now))
        assertTrue(store.hasTranscript(EPISODE))
    }

    @Test
    fun `an episode added to up next during a sweep is retained`() = runTest {
        val now = System.currentTimeMillis()
        oldTranscript(now - 60 * DAY_MS)
        whenever(upNextDao.countByEpisodeBlocking(EPISODE)).thenReturn(0, 1)

        assertEquals(0, queue.expireIdleTranscripts(now))
        assertTrue(store.hasTranscript(EPISODE))
        verify(asr, never()).markExpired(any(), any())
    }

    @Test
    fun `incomplete recognition or detection is retained`() = runTest {
        val now = System.currentTimeMillis()
        oldTranscript(now - 60 * DAY_MS)
        whenever(asr.find(EPISODE)).thenReturn(AsrQueueItem(EPISODE, 0L))
        assertEquals(0, queue.expireIdleTranscripts(now))

        whenever(asr.find(EPISODE)).thenReturn(AsrQueueItem(EPISODE, 0L, isComplete = true))
        whenever(detection.find(EPISODE)).thenReturn(AdDetectionQueueItem(EPISODE, 0L))
        assertEquals(0, queue.expireIdleTranscripts(now))
        assertTrue(store.hasTranscript(EPISODE))
    }

    @Test
    fun `a downloading episode is retained`() = runTest {
        val now = System.currentTimeMillis()
        oldTranscript(now - 60 * DAY_MS)
        whenever(episodes.findByUuid(EPISODE)).thenReturn(episode.copy(downloadStatus = EpisodeDownloadStatus.Downloading))

        assertEquals(0, queue.expireIdleTranscripts(now))
        assertTrue(store.hasTranscript(EPISODE))
    }

    @Test
    fun `expired downloads do not immediately get queued by the library sweep`() = runTest {
        whenever(asr.find(EPISODE)).thenReturn(
            AsrQueueItem(EPISODE, 0L, isComplete = true, lastError = "transcript expired"),
        )
        assertTrue(queue.isRecognised(episode.copy(downloadStatus = EpisodeDownloadStatus.Downloaded)))
    }

    @Test
    fun `requesting an expired transcript restarts recognition`() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        whenever(asr.find(EPISODE)).thenReturn(
            AsrQueueItem(EPISODE, 0L, isComplete = true, lastError = "transcript expired"),
        )

        queue.scheduleLocked(EPISODE)

        verify(asr).reset(EPISODE)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(TranscriptAsrWorker.UNIQUE_NAME).get().isNotEmpty())
    }

    private suspend fun oldTranscript(modifiedAt: Long) {
        store.save(EPISODE, listOf(TranscriptCue(0, 1_000, "hello")))
        assertTrue(store.file(EPISODE).setLastModified(modifiedAt))
    }

    private companion object {
        const val EPISODE = "retention-test-episode"
        const val OTHER_EPISODE = "retention-test-other"
        const val DAY_MS = 24 * 60 * 60 * 1_000L
    }
}
