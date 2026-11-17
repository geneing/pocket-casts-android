package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cue rules, as measured on a device: MediaPipe returns fixed ~1.25 s windows, so the sentence
 * structure has to come from the text and the times from the segments those characters came from.
 *
 * Every test here is a shape that a real pass produced, or the boundary of one: a sentence split
 * across three windows, two sentences inside one window, a window that ends mid-sentence.
 */
class ParakeetCuesTest {
    @Test
    fun `one sentence spread over several windows becomes one cue`() {
        val cues = ParakeetCues.toCues(
            listOf(
                segment("The birch canoe", 0, 1_250),
                segment("slid on the smooth", 1_250, 2_500),
                segment("planks.", 2_500, 3_750),
            ),
            originMs = 0,
            durationMs = 10_000,
        )

        assertEquals(1, cues.size)
        assertEquals("The birch canoe slid on the smooth planks.", cues[0].text)
        assertEquals(0L, cues[0].startMs)
        assertEquals(3_750L, cues[0].endMs)
    }

    @Test
    fun `two sentences inside one window are split proportionally`() {
        // 25 characters over one second: "Hello there." is 12 of them, the second sentence 12 more.
        val cues = ParakeetCues.toCues(
            listOf(segment("Hello there. Goodbye now.", 0, 1_000)),
            originMs = 0,
            durationMs = 10_000,
        )

        assertEquals(2, cues.size)
        assertEquals("Hello there.", cues[0].text)
        assertEquals(0L, cues[0].startMs)
        assertEquals(480L, cues[0].endMs)
        assertEquals("Goodbye now.", cues[1].text)
        assertEquals(520L, cues[1].startMs)
        assertEquals(1_000L, cues[1].endMs)
    }

    @Test
    fun `word-level segments are joined into the sentence they belong to`() {
        val cues = ParakeetCues.toCues(
            listOf(
                segment("Glue", 0, 500),
                segment("the sheet to", 500, 1_500),
                segment("the dark blue", 1_500, 2_500),
                segment("background.", 2_500, 3_500),
            ),
            originMs = 0,
            durationMs = 10_000,
        )

        assertEquals(1, cues.size)
        assertEquals("Glue the sheet to the dark blue background.", cues[0].text)
        assertEquals(3_500L, cues[0].endMs)
    }

    @Test
    fun `a window that stops mid-sentence is folded into the sentence before it`() {
        val cues = ParakeetCues.toCues(
            listOf(
                segment("The leg is a rare dish.", 0, 1_000),
                segment("Rice is often served in round", 1_000, 2_000),
            ),
            originMs = 0,
            durationMs = 10_000,
        )

        assertEquals(1, cues.size)
        assertEquals("The leg is a rare dish. Rice is often served in round", cues[0].text)
        assertEquals(0L, cues[0].startMs)
        assertEquals(2_000L, cues[0].endMs)
    }

    @Test
    fun `an unpunctuated pass over one utterance stays one cue`() {
        val cues = ParakeetCues.toCues(
            listOf(
                segment("no punctuation", 0, 1_000),
                segment("anywhere in here", 1_000, 2_000),
            ),
            originMs = 0,
            durationMs = 10_000,
        )

        assertEquals(1, cues.size)
        assertEquals("no punctuation anywhere in here", cues[0].text)
    }

    @Test
    fun `a closing quote stays with the sentence it closes`() {
        val cues = ParakeetCues.toCues(
            listOf(segment("He said \"done.\" Then he left.", 0, 1_000)),
            originMs = 0,
            durationMs = 10_000,
        )

        assertEquals(2, cues.size)
        assertEquals("He said \"done.\"", cues[0].text)
        assertEquals("Then he left.", cues[1].text)
    }

    @Test
    fun `the origin of a resumed pass is added to every cue`() {
        val cues = ParakeetCues.toCues(
            listOf(segment("Second half.", 1_000, 2_000)),
            originMs = 30_000,
            durationMs = 0,
        )

        assertEquals(1, cues.size)
        assertEquals(31_000L, cues[0].startMs)
        assertEquals(32_000L, cues[0].endMs)
    }

    @Test
    fun `cues are clamped to the episode duration`() {
        val cues = ParakeetCues.toCues(
            listOf(segment("A sentence.", 0, 5_000), segment("Past the end.", 5_000, 9_000)),
            originMs = 0,
            durationMs = 6_000,
        )

        assertEquals(2, cues.size)
        assertEquals(5_000L, cues[0].endMs)
        assertEquals(6_000L, cues[1].endMs)
    }

    @Test
    fun `interim segments are ignored`() {
        val cues = ParakeetCues.toCues(
            listOf(
                segment("Final words.", 0, 1_000),
                segment("still being decoded", 1_000, 2_000, isFinal = false),
            ),
            originMs = 0,
            durationMs = 10_000,
        )

        assertEquals(1, cues.size)
        assertEquals("Final words.", cues[0].text)
        assertEquals(1_000L, cues[0].endMs)
    }

    @Test
    fun `blank text and collapsed segments are dropped`() {
        val cues = ParakeetCues.toCues(
            listOf(
                segment("   ", 0, 1_000),
                segment("Real words.", 2_000, 2_000),
                segment("Kept.", 3_000, 4_000),
            ),
            originMs = 0,
            durationMs = 10_000,
        )

        assertEquals(1, cues.size)
        assertEquals("Kept.", cues[0].text)
    }

    @Test
    fun `segments are laid out in time even when reported out of order`() {
        val cues = ParakeetCues.toCues(
            listOf(
                segment("second sentence.", 1_000, 2_000),
                segment("A first sentence.", 0, 1_000),
            ),
            originMs = 0,
            durationMs = 10_000,
        )

        assertEquals(2, cues.size)
        assertEquals("A first sentence.", cues[0].text)
        assertEquals("second sentence.", cues[1].text)
        assertEquals(0L, cues[0].startMs)
        assertEquals(2_000L, cues[1].endMs)
    }

    @Test
    fun `cues never overlap`() {
        // The same window reported twice under different starts: the text is one sentence, and the
        // cue that comes out must still be monotonic for the VTT writer.
        val cues = ParakeetCues.toCues(
            listOf(
                segment("Overlapping words.", 0, 1_500),
                segment("More words.", 1_000, 2_000),
            ),
            originMs = 0,
            durationMs = 10_000,
        )

        assertTrue(cues.isNotEmpty())
        var previousEnd = 0L
        cues.forEach { cue ->
            assertTrue("${cue.startMs} < $previousEnd", cue.startMs >= previousEnd)
            assertTrue("${cue.endMs} <= ${cue.startMs}", cue.endMs > cue.startMs)
            previousEnd = cue.endMs
        }
    }

    @Test
    fun `no segments means no cues`() {
        assertEquals(
            emptyList<Long>(),
            ParakeetCues.toCues(emptyList(), originMs = 0, durationMs = 10_000).map { it.startMs },
        )
    }

    private fun segment(text: String, startMs: Long, endMs: Long, isFinal: Boolean = true) = RawSegment(text = text, startMs = startMs, endMs = endMs, isFinal = isFinal)
}
