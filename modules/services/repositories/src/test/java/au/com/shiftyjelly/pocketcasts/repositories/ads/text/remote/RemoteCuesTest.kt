package au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCuesTest {
    @Test
    fun `words become sentence cues in episode time`() {
        val transcript = transcriptOf(
            word("Hello", 0.0, 0.5),
            word("there.", 0.5, 1.0),
            word("Goodbye", 1.5, 2.0),
            word("now.", 2.0, 2.5),
        )

        val cues = RemoteCues.toCues(
            transcript = transcript,
            originMs = 60_000,
            fromMs = 0,
            untilMs = Long.MAX_VALUE,
            durationMs = 0,
        )

        assertEquals(listOf("Hello there.", "Goodbye now."), cues.map { it.text })
        // The chunk's own clock starts at zero, so the first word is at the chunk's origin.
        assertEquals(60_000, cues.first().startMs)
        assertEquals(62_500, cues.last().endMs)
    }

    @Test
    fun `only cues that start inside the window are kept, so a seam is not read twice`() {
        val transcript = transcriptOf(
            word("Hello", 0.0, 0.5),
            word("there.", 0.5, 1.0),
            word("Goodbye", 1.5, 2.0),
            word("now.", 2.0, 2.5),
        )

        val cues = RemoteCues.toCues(
            transcript = transcript,
            originMs = 60_000,
            fromMs = 60_000,
            untilMs = 61_000,
            durationMs = 0,
        )

        assertEquals(listOf("Hello there."), cues.map { it.text })
    }

    @Test
    fun `a response without words falls back to the provider's own segments`() {
        val cues = RemoteCues.toCues(
            transcript = RemoteTranscript(
                words = emptyList(),
                segments = listOf(
                    RemoteSegment("One sentence.", 0.0, 1.5),
                    RemoteSegment("Another one.", 1.5, 3.0),
                ),
                audioSeconds = 3.0,
                costUsd = null,
                language = "en",
            ),
            originMs = 0,
            fromMs = 0,
            untilMs = Long.MAX_VALUE,
            durationMs = 0,
        )

        assertEquals(listOf("One sentence.", "Another one."), cues.map { it.text })
    }

    @Test
    fun `a response with only text becomes one cue over the window`() {
        val cues = RemoteCues.toCues(
            transcript = RemoteTranscript(
                words = emptyList(),
                segments = emptyList(),
                text = "All of it.",
                audioSeconds = 4.0,
                costUsd = null,
                language = "en",
            ),
            originMs = 60_000,
            fromMs = 60_000,
            untilMs = 70_000,
            durationMs = 0,
        )

        assertEquals(1, cues.size)
        assertEquals("All of it.", cues.single().text)
        assertEquals(60_000, cues.single().startMs)
        assertEquals(64_000, cues.single().endMs)
    }

    @Test
    fun `cues are clamped to the episode and never overlap`() {
        val transcript = transcriptOf(
            word("First.", 0.0, 1.0),
            word("Second.", 1.0, 100.0),
        )

        val cues = RemoteCues.toCues(
            transcript = transcript,
            originMs = 0,
            fromMs = 0,
            untilMs = Long.MAX_VALUE,
            durationMs = 5_000,
        )

        assertEquals(5_000, cues.last().endMs)
        var previousEnd = 0L
        for (cue in cues) {
            assertTrue("cue ${cue.text} starts before the previous one ended", cue.startMs >= previousEnd)
            previousEnd = cue.endMs
        }
    }

    @Test
    fun `a cue never ends past the audio the request carried`() {
        val transcript = transcriptOf(
            word("Hello", 0.0, 0.5),
            word("world.", 0.5, 9.0),
        )

        val cues = RemoteCues.toCues(
            transcript = transcript,
            originMs = 60_000,
            fromMs = 60_000,
            untilMs = Long.MAX_VALUE,
            durationMs = 0,
            audioMs = 2_000,
        )

        // The model puts the last word seven seconds past the audio it was given; the cue ends
        // where that audio ended, which is what the next chunk has not heard yet.
        assertEquals(62_000, cues.single().endMs)
    }

    @Test
    fun `a cue never starts before the previous chunk's cues ended`() {
        val transcript = transcriptOf(
            word("One.", 0.0, 1.0),
            word("Two", 6.0, 7.0),
            word("three.", 7.0, 8.0),
        )

        val cues = RemoteCues.toCues(
            transcript = transcript,
            originMs = 0,
            fromMs = 0,
            untilMs = Long.MAX_VALUE,
            durationMs = 0,
            notBeforeMs = 6_200,
        )

        // "One." is speech the previous chunk's last cue already ended on, so it is dropped rather
        // than repeated; the next sentence starts where that cue ended.
        assertEquals(listOf("Two three."), cues.map { it.text })
        assertEquals(6_200, cues.single().startMs)
        assertEquals(8_000, cues.single().endMs)
    }

    private fun word(text: String, start: Double, end: Double) = RemoteWord(text, start, end)

    private fun transcriptOf(vararg words: RemoteWord) = RemoteTranscript(
        words = words.toList(),
        segments = emptyList(),
        audioSeconds = words.maxOfOrNull { it.endSeconds } ?: 0.0,
        costUsd = null,
        language = "en",
    )
}
