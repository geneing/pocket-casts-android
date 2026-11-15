package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import au.com.shiftyjelly.pocketcasts.models.db.dao.TranscriptDao
import au.com.shiftyjelly.pocketcasts.models.entity.Transcript
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LocalTranscriptStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val transcriptDao: TranscriptDao = mock()
    private lateinit var store: LocalTranscriptStore

    @Before
    fun setUp() {
        store = LocalTranscriptStore(context, transcriptDao)
        store.file(EPISODE).delete()
    }

    @After
    fun tearDown() {
        store.file(EPISODE).delete()
    }

    @Test
    fun `saved cues become a webvtt file with one cue each, in time order`() = runTest {
        store.save(
            EPISODE,
            listOf(
                TranscriptCue(2_000, 5_500, "second line"),
                TranscriptCue(0, 2_000, "first line"),
            ),
        )

        assertEquals(
            "WEBVTT\n\n" +
                "00:00:00.000 --> 00:00:02.000\nfirst line\n\n" +
                "00:00:02.000 --> 00:00:05.500\nsecond line\n\n",
            store.file(EPISODE).readText(),
        )
    }

    @Test
    fun `a negative time is clamped instead of producing a broken timestamp`() = runTest {
        store.save(EPISODE, listOf(TranscriptCue(-1_500, 3_000, "starts before the episode")))

        assertTrue(store.file(EPISODE).readText().contains("00:00:00.000 --> 00:00:03.000"))
    }

    @Test
    fun `saving registers the transcript under the local scheme`() = runTest {
        store.save(EPISODE, listOf(TranscriptCue(0, 1_000, "hello")))

        val captor = argumentCaptor<Transcript>()
        verify(transcriptDao).upsert(captor.capture())
        assertEquals("${LocalTranscriptStore.SCHEME}$EPISODE", captor.firstValue.url)
        assertEquals("text/vtt", captor.firstValue.type)
        assertTrue(captor.firstValue.isGenerated)
    }

    @Test
    fun `a saved transcript is present and deleting removes the file and the row`() = runTest {
        store.save(EPISODE, listOf(TranscriptCue(0, 1_000, "hello")))
        assertTrue(store.hasTranscript(EPISODE))

        store.delete(EPISODE)

        assertFalse(store.hasTranscript(EPISODE))
        verify(transcriptDao).deleteGenerated(EPISODE, "${LocalTranscriptStore.SCHEME}$EPISODE")
    }

    private companion object {
        const val EPISODE = "moonshine-store-test-episode"
    }
}
