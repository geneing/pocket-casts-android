package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The windowing rule: 10 s blocks stepping 8 s, because that is the window the Redux encoder holds.
 *
 * A window that is off by a sample would shift every cue of the pass, so the interesting cases are
 * the odd chunk sizes [au.com.shiftyjelly.pocketcasts.repositories.ads.text.AudioFeed] really
 * produces, the overlap, and the tail of the last window.
 */
class ParakeetWindowBufferTest {
    private val rate = ParakeetWindowBuffer.SAMPLE_RATE
    private val windowSamples = ParakeetWindowBuffer.WINDOW_SAMPLES
    private val strideSamples = 8 * rate

    private val windows = mutableListOf<Pair<Long, FloatArray>>()
    private val buffer = ParakeetWindowBuffer { startMs, samples -> windows.add(startMs to samples) }

    @Test
    fun `one window is emitted for ten seconds and it starts at zero`() {
        feed(ramp(0, 10 * rate), chunk = 1_024)

        assertEquals(listOf(0L), windows.map { it.first })
        val window = windows.single().second
        assertEquals(windowSamples, window.size)
        assertEquals(0f, window.first(), 0f)
        assertEquals(10f - 1f / rate, window.last(), 0.001f)
    }

    @Test
    fun `windows start every eight seconds and each covers ten`() {
        feed(ramp(0, 18 * rate), chunk = 1_333)

        assertEquals(listOf(0L, 8_000L), windows.map { it.first })
        val second = windows[1].second
        // The window's first sample is the audio at 8 s and the one 8 s in is the audio at 16 s: the
        // second window re-covers the last 2 s of the first one.
        assertEquals(8f, second.first(), 0.001f)
        assertEquals(16f, second[strideSamples], 0.001f)
    }

    @Test
    fun `a tail beyond the overlap is a window of its own, zero padded`() {
        // 12.5 s: one window at 0, then 4.5 s beyond the overlap the next window would have covered.
        val audio = ramp(0, 12 * rate + rate / 2)
        feed(audio, chunk = 4_096)
        buffer.finish()

        assertEquals(listOf(0L, 8_000L), windows.map { it.first })
        val tail = windows[1].second
        assertEquals(windowSamples, tail.size)
        assertEquals(8f, tail.first(), 0.001f)
        val validTail = 12 * rate + rate / 2 - strideSamples
        assertEquals(audio.last(), tail[validTail - 1], 0.001f)
        assertTrue(tail.drop(validTail).all { it == 0f })
    }

    @Test
    fun `audio inside the last overlap is not a window, it is already recognised`() {
        // Exactly one window: the 2 s it leaves over are the overlap with a window that never comes.
        feed(ramp(0, windowSamples), chunk = 8_000)
        buffer.finish()

        assertEquals(listOf(0L), windows.map { it.first })
    }

    @Test
    fun `a pass shorter than one window is still one window`() {
        feed(ramp(0, rate), chunk = 512)
        buffer.finish()

        assertEquals(listOf(0L), windows.map { it.first })
        val only = windows.single().second
        assertEquals(windowSamples, only.size)
        assertEquals(1f - 1f / rate, only[rate - 1], 0.001f)
        assertTrue(only.drop(rate).all { it == 0f })
    }

    @Test
    fun `a sliver too short for two mel frames is not a window`() {
        feed(ramp(0, ParakeetWindowBuffer.MIN_WINDOW_SAMPLES - 1), chunk = 1_024)
        buffer.finish()

        assertEquals(emptyList<Long>(), windows.map { it.first })
    }

    @Test
    fun `an empty feed emits nothing`() {
        feed(FloatArray(0), chunk = 512)
        buffer.finish()

        assertEquals(emptyList<Long>(), windows.map { it.first })
    }

    private fun feed(values: FloatArray, chunk: Int) {
        var offset = 0
        while (offset < values.size) {
            val size = minOf(chunk, values.size - offset)
            buffer.accept(values.copyOfRange(offset, offset + size))
            offset += size
        }
    }

    /** A ramp whose sample value is its time in seconds, so a window's offsets are readable. */
    private fun ramp(fromSample: Int, samples: Int): FloatArray = FloatArray(samples) { index ->
        (fromSample + index).toFloat() / rate
    }
}
