package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of [MlKitEpisodeTranscriber] that do not need ML Kit or a device: how speech regions
 * are found in frame energies, and how ordered ASR segments become timed cues. The ML Kit client
 * itself is never constructed here.
 */
class MlKitEpisodeTranscriberTest {
    private fun segment(text: String) = RecognizedSegment(text)

    private fun region(startMs: Long, endMs: Long) = SpeechRegion(startMs, endMs)

    /** Builds a level track from (dBFS, frame count) blocks, 20 ms each. */
    private fun levels(vararg blocks: Pair<Float, Int>): FloatArray {
        val out = ArrayList<Float>()
        for ((level, frames) in blocks) repeat(frames) { out.add(level) }
        return out.toFloatArray()
    }

    private val speech = -20f
    private val silence = -70f
    private val frameMs = 20L

    @Test
    fun `one segment per region uses the region span`() {
        val cues = alignSegmentsToRegions(
            segments = listOf(segment("brought to you by acme"), segment("use code save")),
            regions = listOf(region(1_000, 2_000), region(3_000, 4_500)),
            durationMs = 60_000,
        )

        assertEquals(2, cues.size)
        assertEquals(TranscriptCue(1_000, 2_000, "brought to you by acme"), cues[0])
        assertEquals(TranscriptCue(3_000, 4_500, "use code save"), cues[1])
    }

    @Test
    fun `more segments than regions split the speech time by text length`() {
        val cues = alignSegmentsToRegions(
            segments = listOf(segment("abcd"), segment("abcdefghij")),
            regions = listOf(region(1_000, 3_000)),
            durationMs = 60_000,
        )

        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals("the cues are contiguous", cues[0].endMs, cues[1].startMs)
        assertTrue(
            "the longer text gets the longer span",
            cues[1].endMs - cues[1].startMs > cues[0].endMs - cues[0].startMs,
        )
        assertEquals(3_000L, cues[1].endMs)
    }

    @Test
    fun `fewer segments than regions still cover only speech spans`() {
        val cues = alignSegmentsToRegions(
            segments = listOf(segment("one long sentence")),
            regions = listOf(region(1_000, 2_000), region(3_000, 4_000)),
            durationMs = 60_000,
        )

        assertEquals(1, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(4_000L, cues[0].endMs)
    }

    @Test
    fun `proportional cues start where the previous one ended and never overlap`() {
        val cues = alignSegmentsToRegions(
            segments = listOf(segment("first"), segment("second one here"), segment("third")),
            regions = listOf(region(1_000, 2_000), region(5_000, 6_000)),
            durationMs = 60_000,
        )

        assertEquals(3, cues.size)
        cues.forEach { cue -> assertTrue("cue ${cue.text} ends before it starts", cue.endMs >= cue.startMs) }
        cues.zipWithNext { earlier, later ->
            assertTrue("cues must not overlap", later.startMs >= earlier.endMs)
        }
        assertTrue(cues.last().endMs <= 60_000)
    }

    @Test
    fun `a cue never passes the episode duration`() {
        val cues = alignSegmentsToRegions(
            segments = listOf(segment("use code save twenty percent")),
            regions = listOf(region(1_000, 5_000)),
            durationMs = 3_000,
        )

        assertEquals(1, cues.size)
        assertEquals(3_000L, cues[0].endMs)
    }

    @Test
    fun `without regions the cues are spread across the duration by text length`() {
        val cues = alignSegmentsToRegions(
            segments = listOf(segment("abcd"), segment("abcdefghij")),
            regions = emptyList(),
            durationMs = 10_000,
        )

        assertEquals(2, cues.size)
        assertEquals(0L, cues[0].startMs)
        assertEquals(cues[0].endMs, cues[1].startMs)
        assertTrue("the split follows the text lengths", cues[0].endMs in 2_000L..4_000L)
        assertTrue(cues[1].endMs <= 10_000L)
    }

    @Test
    fun `without regions and without a duration there is nowhere to put cues`() {
        val cues = alignSegmentsToRegions(
            segments = listOf(segment("use code save")),
            regions = emptyList(),
            durationMs = 0,
        )

        assertTrue(cues.isEmpty())
    }

    @Test
    fun `blank segments are dropped`() {
        val cues = alignSegmentsToRegions(
            segments = listOf(segment("   "), segment("kept"), segment("")),
            regions = listOf(region(1_000, 2_000)),
            durationMs = 60_000,
        )

        assertEquals(1, cues.size)
        assertEquals("kept", cues[0].text)
        assertEquals(TranscriptCue(1_000, 2_000, "kept"), cues[0])
    }

    @Test
    fun `overlapping regions are merged before aligning`() {
        val cues = alignSegmentsToRegions(
            segments = listOf(segment("use code save")),
            regions = listOf(region(1_000, 3_000), region(2_000, 4_000)),
            durationMs = 60_000,
        )

        assertEquals(TranscriptCue(1_000, 4_000, "use code save"), cues.single())
    }

    @Test
    fun `speech regions merge short pauses and drop blips`() {
        val track = levels(
            silence to 20, // 0-400 ms
            speech to 10, // 400-600 ms
            silence to 15, // 300 ms pause, inside the merge gap
            speech to 5, // 900-1000 ms
            silence to 10,
        )

        val regions = detectSpeechRegions(track, frameMs)

        assertEquals(listOf(region(400, 1_000)), regions)
    }

    @Test
    fun `a run shorter than the minimum region is not speech`() {
        val track = levels(silence to 20, speech to 5, silence to 20)

        val regions = detectSpeechRegions(track, frameMs)

        assertTrue(regions.isEmpty())
    }

    @Test
    fun `the threshold follows the clip's own level`() {
        val quiet = levels(silence to 20, speech to 10, silence to 10)
        val loud = FloatArray(quiet.size) { quiet[it] + 30f }

        assertEquals(
            detectSpeechRegions(quiet, frameMs),
            detectSpeechRegions(loud, frameMs),
        )
        assertEquals(listOf(region(400, 600)), detectSpeechRegions(quiet, frameMs))
    }

    @Test
    fun `a clip without dynamic range is one region`() {
        val flat = levels(speech to 50)

        val regions = detectSpeechRegions(flat, frameMs)

        assertEquals(listOf(region(0, 1_000)), regions)
    }

    @Test
    fun `an empty or untiled track has no regions`() {
        assertTrue(detectSpeechRegions(FloatArray(0), frameMs).isEmpty())
        assertTrue(detectSpeechRegions(levels(speech to 10), 0).isEmpty())
    }
}
