package au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineAudioFeedTest {
    @Test
    fun `PCM16 is scaled into minus one to one`() {
        val pcm = shortArrayOf(0, 16_384, -32_768, 32_767)

        assertArrayEquals(floatArrayOf(0f, 0.5f, -1f, 32_767f / 32_768f), pcm16ToFloat(pcm, pcm.size), 0.00001f)
    }

    @Test
    fun `stereo is averaged down to mono`() {
        val stereo = floatArrayOf(1f, 0f, 0.5f, 0.25f, -1f, -0.25f)

        assertArrayEquals(floatArrayOf(0.5f, 0.375f, -0.625f), toMono(stereo, stereo.size, channelCount = 2), 0.00001f)
    }

    @Test
    fun `mono passes through untouched`() {
        val mono = floatArrayOf(0.25f, -0.25f)

        assertArrayEquals(mono, toMono(mono, mono.size, channelCount = 1), 0.0f)
    }

    @Test
    fun `a buffer's mono conversion keeps its own sample rate`() {
        val buffer = pcm16Buffer(
            samples = shortArrayOf(16_384, 0, -16_384, 0),
            channelCount = 2,
            sampleRate = 44_100,
            presentationTimeUs = 0L,
        )

        assertArrayEquals(floatArrayOf(0.25f, -0.25f), buffer.monoFloats(), 0.00001f)
    }

    @Test
    fun `positions follow the sample count across chunk boundaries`() {
        val chunks = record { pacer ->
            pacer.accept(sampleRate = 1_000, presentationTimeUs = 0L, mono = FloatArray(500))
            pacer.accept(sampleRate = 1_000, presentationTimeUs = 500_000L, mono = FloatArray(500))
            pacer.accept(sampleRate = 1_000, presentationTimeUs = 1_000_000L, mono = FloatArray(250))
        }

        assertEquals(listOf(0L, 500L, 1_000L), chunks.map { it.positionMs })
        assertEquals(1_250L, chunks.last().positionMs + 250L)
    }

    @Test
    fun `a skipped buffer is padded with silence so later positions stay on the timeline`() {
        val chunks = record { pacer ->
            pacer.accept(sampleRate = 1_000, presentationTimeUs = 0L, mono = FloatArray(1_000))
            // The buffer covering 1 s..2 s never arrived; the next one starts at 2 s.
            pacer.accept(sampleRate = 1_000, presentationTimeUs = 2_000_000L, mono = FloatArray(1_000))
        }

        assertEquals(3, chunks.size)
        assertEquals(0L, chunks[0].positionMs)
        assertEquals(1_000L, chunks[1].positionMs)
        assertEquals(1_000, chunks[1].samples.size)
        assertTrue(chunks[1].samples.all { it == 0f })
        assertEquals(2_000L, chunks[2].positionMs)
    }

    @Test
    fun `a gap smaller than the tolerance is left alone instead of inserting silence`() {
        val chunks = record { pacer ->
            pacer.accept(sampleRate = 1_000, presentationTimeUs = 0L, mono = FloatArray(1_000))
            // 50 ms of drift: inside the accuracy of the cues we store.
            pacer.accept(sampleRate = 1_000, presentationTimeUs = 1_050_000L, mono = FloatArray(1_000))
        }

        assertEquals(2, chunks.size)
        // The position comes from the audio fed, so it does not jump to the container's 1050 ms.
        assertEquals(1_000L, chunks[1].positionMs)
    }

    @Test
    fun `the first buffer defines the origin so playback starts at zero`() {
        val chunks = record { pacer ->
            pacer.accept(sampleRate = 1_000, presentationTimeUs = 500_000L, mono = FloatArray(1_000))
            pacer.accept(sampleRate = 1_000, presentationTimeUs = 1_500_000L, mono = FloatArray(500))
        }

        assertEquals(listOf(0L, 1_000L), chunks.map { it.positionMs })
    }

    private fun record(block: (FeedPacer) -> Unit): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        val pacer = FeedPacer { samples, _, positionMs -> chunks.add(Chunk(samples, positionMs)) }
        block(pacer)
        return chunks
    }

    private fun pcm16Buffer(
        samples: ShortArray,
        channelCount: Int,
        sampleRate: Int,
        presentationTimeUs: Long,
    ) = au.com.shiftyjelly.pocketcasts.repositories.ads.AudioAdDecoder.PcmBuffer(
        pcm16 = samples,
        floats = null,
        sampleCount = samples.size,
        channelCount = channelCount,
        sampleRate = sampleRate,
        positionMs = 0L,
        presentationTimeUs = presentationTimeUs,
    )

    private data class Chunk(val samples: FloatArray, val positionMs: Long)
}
