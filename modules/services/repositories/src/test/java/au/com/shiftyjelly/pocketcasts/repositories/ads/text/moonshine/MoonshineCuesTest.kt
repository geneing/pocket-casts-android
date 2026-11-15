package au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine

import ai.moonshine.voice.TranscriptLine
import ai.moonshine.voice.WordTiming
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineCuesTest {
    @Test
    fun `a cue starts at the first word and ends at the last word, not at the VAD window`() {
        val lines = listOf(
            line(
                text = "this episode is brought to you by acme",
                startTime = 10f,
                duration = 6f,
                words = arrayOf(
                    word("this", 10.8f, 11.0f),
                    word("episode", 11.0f, 11.4f),
                    word("acme", 15.2f, 15.6f),
                ),
            ),
        )

        assertEquals(listOf(TranscriptCue(10_800, 15_600, "this episode is brought to you by acme")), cues(lines, 0))
    }

    @Test
    fun `a line without words falls back to its own start and duration, which is all Spanish has`() {
        val lines = listOf(line(text = "este episodio es un anuncio", startTime = 12.5f, duration = 4.25f))

        assertEquals(listOf(TranscriptCue(12_500, 16_750, "este episodio es un anuncio")), cues(lines, 0))
    }

    @Test
    fun `partial lines are dropped because their duration is provisional`() {
        val lines = listOf(
            line(text = "this part is not final", startTime = 0f, duration = 2f, isComplete = false),
            line(text = "this part is final.", startTime = 3f, duration = 2f),
        )

        assertEquals(listOf(TranscriptCue(3_000, 5_000, "this part is final.")), cues(lines, 0))
    }

    @Test
    fun `lines are returned in time order even when the decoder reports them out of order`() {
        val lines = listOf(
            line(text = "second.", startTime = 20f, duration = 2f),
            line(text = "first.", startTime = 5f, duration = 2f),
        )

        assertEquals(
            listOf(TranscriptCue(5_000, 7_000, "first."), TranscriptCue(20_000, 22_000, "second.")),
            cues(lines, 0),
        )
    }

    @Test
    fun `times are clamped to the episode duration`() {
        val lines = listOf(
            line(text = "negative.", startTime = -1f, duration = 3f),
            line(text = "past the end.", startTime = 58f, duration = 5f),
        )

        assertEquals(
            listOf(TranscriptCue(0, 2_000, "negative."), TranscriptCue(58_000, 60_000, "past the end.")),
            cues(lines, 60_000),
        )
    }

    @Test
    fun `an overlapping line starts where the previous cue ended`() {
        val lines = listOf(
            line(text = "first.", startTime = 0f, duration = 10f),
            line(text = "second.", startTime = 8f, duration = 4f),
        )

        assertEquals(
            listOf(TranscriptCue(0, 10_000, "first."), TranscriptCue(10_000, 12_000, "second.")),
            cues(lines, 0),
        )
    }

    @Test
    fun `a mid-sentence VAD split is merged into the line it continues`() {
        val lines = listOf(
            line(text = "we will be right back after", startTime = 0f, duration = 2f),
            line(text = "this word from our sponsor.", startTime = 2.1f, duration = 2f),
        )

        assertEquals(
            listOf(TranscriptCue(0, 4_100, "we will be right back after this word from our sponsor.")),
            cues(lines, 0),
        )
    }

    @Test
    fun `a long pause keeps two lines apart even without punctuation`() {
        val lines = listOf(
            line(text = "so that is the story", startTime = 0f, duration = 2f),
            line(text = "anyway here is the next thing.", startTime = 2.5f, duration = 2f),
        )

        assertEquals(
            listOf(
                TranscriptCue(0, 2_000, "so that is the story"),
                TranscriptCue(2_500, 4_500, "anyway here is the next thing."),
            ),
            cues(lines, 0),
        )
    }

    @Test
    fun `blank text and zero-length lines are dropped`() {
        val lines = listOf(
            line(text = "   ", startTime = 0f, duration = 2f),
            line(text = "no time at all", startTime = 5f, duration = 0f),
            line(text = "a real line.", startTime = 6f, duration = 1f),
        )

        assertEquals(listOf(TranscriptCue(6_000, 7_000, "a real line.")), cues(lines, 0))
    }

    @Test
    fun `no complete lines means no cues`() {
        assertTrue(cues(emptyList(), 60_000).isEmpty())
    }

    private fun line(
        text: String,
        startTime: Float,
        duration: Float,
        isComplete: Boolean = true,
        words: Array<WordTiming> = emptyArray(),
    ) = TranscriptLine().apply {
        this.text = text
        this.startTime = startTime
        this.duration = duration
        this.isComplete = isComplete
        this.words = words.toList()
    }

    private fun word(text: String, start: Float, end: Float) = WordTiming().apply {
        this.word = text
        this.start = start
        this.end = end
        this.confidence = 1f
    }
}
