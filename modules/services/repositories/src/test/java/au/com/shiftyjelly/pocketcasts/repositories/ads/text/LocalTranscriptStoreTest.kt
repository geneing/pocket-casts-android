package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import au.com.shiftyjelly.pocketcasts.models.db.dao.TranscriptDao
import au.com.shiftyjelly.pocketcasts.models.entity.Transcript
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
        store.file(EPISODE).parentFile?.resolve("$EPISODE.complete")?.delete()
        store.file(EPISODE).parentFile?.resolve("$EPISODE.audio")?.delete()
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

    /**
     * Recognition rewrites the file every few seconds while the transcript tab may be reading it.
     * Writing in place truncates first, and a reader in that window parses a half-written cue and
     * throws, which left a finished episode with no transcript on screen. The write goes through a
     * sibling temporary file and a rename, so there is nothing half-written to see.
     */
    @Test
    fun `rewriting leaves no temporary file and always a complete transcript`() = runTest {
        repeat(5) { index ->
            store.saveProgress(EPISODE, (0..index).map { TranscriptCue(it * 1_000L, it * 1_000L + 900, "line $it") })

            assertEquals("line $index", store.readCues(EPISODE).last().text)
            assertFalse(
                "a save left its temporary file behind",
                store.file(EPISODE).parentFile?.resolve("$EPISODE.vtt.tmp")?.exists() ?: false,
            )
        }
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

    @Test
    fun `progress rewritten during recognition reads back the latest cues`() = runTest {
        store.saveProgress(EPISODE, listOf(TranscriptCue(0, 2_000, "first line")))
        assertEquals(listOf(TranscriptCue(0, 2_000, "first line")), store.readCues(EPISODE))

        store.saveProgress(
            EPISODE,
            listOf(
                TranscriptCue(0, 2_000, "first line"),
                TranscriptCue(2_000, 5_500, "second line"),
            ),
        )

        assertEquals(
            listOf(
                TranscriptCue(0, 2_000, "first line"),
                TranscriptCue(2_000, 5_500, "second line"),
            ),
            store.readCues(EPISODE),
        )
    }

    @Test
    fun `a completion marker pins the transcript to the engine that finished it`() = runTest {
        store.markComplete(EPISODE, "engine-v1")
        assertTrue(store.isComplete(EPISODE, "engine-v1"))
        assertFalse(store.isComplete(EPISODE, "engine-v2"))

        store.delete(EPISODE)
        assertFalse(store.isComplete(EPISODE, "engine-v1"))
    }

    @Test
    fun `completion wakes a waiting transcript observer`() = runTest {
        val update = async(start = CoroutineStart.UNDISPATCHED) { store.updates.first() }

        store.markComplete(EPISODE, "engine-v1")

        assertEquals(EPISODE, update.await())
    }

    /**
     * The stream and the download of one episode can carry different ad stitches, so a transcript
     * has to remember which audio it heard: it is the only way to know a finished transcript cannot
     * be shown against the audio playing now.
     */
    @Test
    fun `a completion marker records the audio the transcript was recognised from`() = runTest {
        store.markComplete(EPISODE, "engine-v1", audioSignature = "1000:1700000000000")

        assertEquals("1000:1700000000000", store.recognisedAudio(EPISODE))
        assertTrue(store.isComplete(EPISODE, "engine-v1"))

        store.recordAudio(EPISODE, "2000:1800000000000")
        store.markComplete(EPISODE, "engine-v1")
        assertEquals("2000:1800000000000", store.recognisedAudio(EPISODE))
    }

    @Test
    fun `audio recorded before a pass survives a process death without claiming a version`() = runTest {
        store.recordAudio(EPISODE, "1000:1700000000000")

        assertEquals("1000:1700000000000", store.recognisedAudio(EPISODE))
        assertFalse("an unfinished pass is not a finished transcript", store.isComplete(EPISODE, "engine-v1"))
    }

    @Test
    fun `a marker from before the audio was recorded has no signature`() = runTest {
        store.markComplete(EPISODE, "engine-v1")
        assertTrue(store.isComplete(EPISODE, "engine-v1"))
        assertEquals(null, store.recognisedAudio(EPISODE))
    }

    @Test
    fun `deleting a transcript deletes the audio it recorded`() = runTest {
        store.recordAudio(EPISODE, "1000:1700000000000")
        store.markComplete(EPISODE, "engine-v1")

        store.delete(EPISODE)

        assertEquals(null, store.recognisedAudio(EPISODE))
    }

    private companion object {
        const val EPISODE = "transcript-store-test-episode"
    }
}
