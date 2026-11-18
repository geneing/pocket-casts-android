package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log-mel front end's shape and its one hard rule: silence is silence.
 *
 * The values themselves come from jLibrosa, which is the same implementation the reference ASR app
 * uses, so there is nothing to pin here beyond the layout the encoder expects. What is worth pinning
 * is that a window of silence normalises to zeros rather than to noise, because a graph fed amplified
 * noise hallucinates text and that would show up as words in a silent episode.
 */
class ParakeetMelTest {
    private val mel = ParakeetMel()

    @Test
    fun `five seconds of audio becomes the encoder's flat mel block`() {
        val features = mel.process(tone(seconds = 5.0))

        // 128 mel bins x 500 frames, bin-major, which is what the graph's [1, 128, 500] input wants.
        assertEquals(128 * 500, features.size)
        assertTrue("features should be finite", features.all { it.isFinite() })
        assertTrue("a tone is not silence", features.any { it != 0f })
    }

    @Test
    fun `silence normalises to zeros instead of to amplified noise`() {
        val features = mel.process(FloatArray(5 * ParakeetMel.SAMPLE_RATE))

        assertTrue(features.all { it == 0f })
    }

    @Test
    fun `a short window is padded to the encoder's frame count`() {
        val features = mel.process(tone(seconds = 1.0))

        assertEquals(128 * 500, features.size)
        assertTrue(features.all { it.isFinite() })
    }

    @Test
    fun `an empty buffer produces nothing rather than a crash`() {
        assertEquals(0, mel.process(FloatArray(0)).size)
    }

    /** A 440 Hz tone, which is speech-like enough for a shape test and cheap to generate. */
    private fun tone(seconds: Double): FloatArray {
        val samples = (seconds * ParakeetMel.SAMPLE_RATE).toInt()
        return FloatArray(samples) { index ->
            (0.5 * sin(2.0 * PI * 440.0 * index / ParakeetMel.SAMPLE_RATE)).toFloat()
        }
    }
}
