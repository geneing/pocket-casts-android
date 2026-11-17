package au.com.shiftyjelly.pocketcasts.repositories.ads

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdSignatureTest {
    @Test
    fun `similar audio produces a matching signature`() {
        val first = AdSignature.build(frames(startMs = 10_000, bandSlope = 0.4f), startTimeMs = 10_000)
        val second = AdSignature.build(frames(startMs = 50_000, bandSlope = 0.4f), startTimeMs = 50_000)

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(AdSignature.similarity(first!!, second!!) > 0.99f)
    }

    @Test
    fun `different audio does not match`() {
        val first = AdSignature.build(frames(startMs = 10_000, bandSlope = 0.4f), startTimeMs = 10_000)
        val second = AdSignature.build(frames(startMs = 50_000, bandSlope = -0.4f), startTimeMs = 50_000)

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(AdSignature.similarity(first!!, second!!) < 0.9f)
    }

    @Test
    fun `missing frames produce no signature`() {
        assertNull(AdSignature.build(emptyList(), startTimeMs = 0))
    }

    private fun frames(startMs: Long, bandSlope: Float): List<AudioFrame> {
        val frames = mutableListOf<AudioFrame>()
        var position = startMs
        var index = 0
        repeat(40) {
            frames += AudioFrame(
                positionMs = position,
                rmsDb = -20f,
                zeroCrossingRate = 0.05f,
                spectralCentroidHz = 1_000f,
                spectralFlatness = 0.05f,
                channelCorrelation = 1f,
                melBands = FloatArray(AudioFeatureExtractor.MEL_BAND_COUNT) { band ->
                    3f + band * bandSlope + (index % 5) * 0.6f
                },
            )
            position += 100
            index++
        }
        return frames
    }
}
