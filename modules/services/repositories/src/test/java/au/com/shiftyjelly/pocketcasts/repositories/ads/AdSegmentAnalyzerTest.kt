package au.com.shiftyjelly.pocketcasts.repositories.ads

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdSegmentAnalyzerTest {
    private val hopMs = 20L

    @Test
    fun `finds an inserted block between two short silences`() {
        val frames = mutableListOf<AudioFrame>()
        frames += frames(fromMs = 0, toMs = 10_000, rmsDb = -20f, melValue = 1f)
        frames += silenceFrames(fromMs = 10_000, durationMs = 120)
        frames += frames(fromMs = 10_120, toMs = 25_120, rmsDb = -13f, melValue = 3f)
        frames += silenceFrames(fromMs = 25_120, durationMs = 120)
        frames += frames(fromMs = 25_240, toMs = 40_000, rmsDb = -20f, melValue = 1f)

        val candidates = AdSegmentAnalyzer.analyze(frames)

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertTrue("start was ${candidate.startTimeMs}", abs(candidate.startTimeMs - 10_120L) <= 300L)
        assertTrue("end was ${candidate.endTimeMs}", abs(candidate.endTimeMs - 25_120L) <= 300L)
        assertTrue(candidate.confidence >= AdSegmentAnalyzer.MARKER_THRESHOLD)
        assertTrue(AdSegmentAnalyzer.CueKind.Silence in candidate.cues)
    }

    @Test
    fun `leaves uniform speech alone`() {
        val frames = frames(fromMs = 0, toMs = 60_000, rmsDb = -20f, melValue = 1f)

        assertTrue(AdSegmentAnalyzer.analyze(frames).isEmpty())
    }

    @Test
    fun `a quiet block without boundary cues is not reported`() {
        val frames = mutableListOf<AudioFrame>()
        frames += frames(fromMs = 0, toMs = 30_000, rmsDb = -20f, melValue = 1f)
        frames += frames(fromMs = 30_000, toMs = 60_000, rmsDb = -45f, melValue = 1f)

        assertTrue(AdSegmentAnalyzer.analyze(frames).isEmpty())
    }

    private fun frames(fromMs: Long, toMs: Long, rmsDb: Float, melValue: Float): List<AudioFrame> {
        val frames = mutableListOf<AudioFrame>()
        var position = fromMs
        while (position < toMs) {
            frames += AudioFrame(
                positionMs = position,
                rmsDb = rmsDb,
                zeroCrossingRate = 0.05f,
                spectralCentroidHz = 1_200f,
                spectralFlatness = 0.05f,
                channelCorrelation = 1f,
                melBands = FloatArray(AudioFeatureExtractor.MEL_BAND_COUNT) { melValue },
            )
            position += hopMs
        }
        return frames
    }

    private fun silenceFrames(fromMs: Long, durationMs: Long): List<AudioFrame> {
        return frames(fromMs = fromMs, toMs = fromMs + durationMs, rmsDb = -70f, melValue = 0.1f)
    }
}
