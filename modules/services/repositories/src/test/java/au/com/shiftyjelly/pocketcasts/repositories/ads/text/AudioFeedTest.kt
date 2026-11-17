package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.repositories.ads.AudioAdDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feed rules that matter to the recognizer: one constant rate, positions that follow the audio
 * actually handed over, and silence where the container lost a buffer.
 *
 * The resampler is the reason this file exists. A rate change used to be harmless because the
 * recognizer resampled internally; now the session is opened once at 16 kHz, so a buffer that
 * arrives at another rate has to be converted here or the session is fed audio it cannot interpret.
 */
class AudioFeedTest {
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
    fun `audio already at the recognizer's rate is handed over untouched`() {
        val mono = FloatArray(AudioFeed.TARGET_SAMPLE_RATE) { it / AudioFeed.TARGET_SAMPLE_RATE.toFloat() }

        val chunks = record { pacer ->
            pacer.accept(AudioFeed.TARGET_SAMPLE_RATE, 0L, mono)
            pacer.accept(AudioFeed.TARGET_SAMPLE_RATE, 1_000_000L, mono)
        }

        assertEquals(2, chunks.size)
        assertEquals(AudioFeed.TARGET_SAMPLE_RATE, chunks[0].sampleRate)
        // A chunk is stamped with where it starts, so the second one starts one second in.
        assertEquals(listOf(0L, 1_000L), chunks.map { it.positionMs })
        assertEquals(mono.size, chunks[0].samples.size)
    }

    @Test
    fun `a buffer at another rate is resampled to the recognizer's rate`() {
        // One second of 44.1 kHz audio, the rate a downloaded MP3 or M4A usually decodes at.
        val mono = FloatArray(44_100) { 0.5f }

        val chunks = record { pacer ->
            pacer.accept(44_100, 0L, mono)
        }

        assertEquals(AudioFeed.TARGET_SAMPLE_RATE, chunks[0].sampleRate)
        // A constant signal resamples to the same constant, one second of it at 16 kHz.
        assertTrue("fed ${chunks[0].samples.size} samples", chunks[0].samples.size in 15_990..16_000)
        assertTrue(chunks[0].samples.all { it == 0.5f })
        assertEquals(0L, chunks[0].positionMs)
    }

    @Test
    fun `a rate change mid-file keeps one output rate and one clock`() {
        val chunks = record { pacer ->
            pacer.accept(44_100, 0L, FloatArray(44_100) { 0.25f })
            // The same file switching to 48 kHz: the session was opened at 16 kHz and must never
            // see a different number here.
            pacer.accept(48_000, 1_000_000L, FloatArray(48_000) { 0.75f })
        }

        assertEquals(listOf(AudioFeed.TARGET_SAMPLE_RATE, AudioFeed.TARGET_SAMPLE_RATE), chunks.map { it.sampleRate })
        assertEquals(0L, chunks[0].positionMs)
        // The second second starts where the first ended, not back at zero.
        assertEquals(1_000L, chunks[1].positionMs)
        assertEquals(0.25f, chunks[0].samples.first(), 0.00001f)
        assertEquals(0.75f, chunks[1].samples.first(), 0.00001f)
    }

    @Test
    fun `positions follow the audio fed across chunk boundaries`() {
        val chunks = record { pacer ->
            pacer.accept(AudioFeed.TARGET_SAMPLE_RATE, 0L, FloatArray(8_000))
            pacer.accept(AudioFeed.TARGET_SAMPLE_RATE, 500_000L, FloatArray(8_000))
            pacer.accept(AudioFeed.TARGET_SAMPLE_RATE, 1_000_000L, FloatArray(4_000))
        }

        assertEquals(listOf(0L, 500L, 1_000L), chunks.map { it.positionMs })
        assertEquals(1_250L, chunks.last().positionMs + 250L)
    }

    @Test
    fun `a skipped buffer is padded with silence so later positions stay on the timeline`() {
        val chunks = record { pacer ->
            pacer.accept(AudioFeed.TARGET_SAMPLE_RATE, 0L, FloatArray(AudioFeed.TARGET_SAMPLE_RATE))
            // The buffer covering 1 s..2 s never arrived; the next one starts at 2 s.
            pacer.accept(AudioFeed.TARGET_SAMPLE_RATE, 2_000_000L, FloatArray(AudioFeed.TARGET_SAMPLE_RATE))
        }

        assertEquals(3, chunks.size)
        assertEquals(0L, chunks[0].positionMs)
        assertTrue("silence starts at ${chunks[1].positionMs}", chunks[1].positionMs in 999L..1_000L)
        assertEquals(AudioFeed.TARGET_SAMPLE_RATE, chunks[1].samples.size)
        assertTrue(chunks[1].samples.all { it == 0f })
        assertEquals(2_000L, chunks[2].positionMs)
    }

    @Test
    fun `a gap smaller than the tolerance is left alone instead of inserting silence`() {
        val chunks = record { pacer ->
            pacer.accept(AudioFeed.TARGET_SAMPLE_RATE, 0L, FloatArray(AudioFeed.TARGET_SAMPLE_RATE))
            // 50 ms of drift: inside the accuracy of the cues we store.
            pacer.accept(AudioFeed.TARGET_SAMPLE_RATE, 1_050_000L, FloatArray(AudioFeed.TARGET_SAMPLE_RATE))
        }

        assertEquals(2, chunks.size)
        // The position comes from the audio fed, so it does not jump to the container's 1050 ms.
        assertEquals(1_000L, chunks[1].positionMs)
    }

    @Test
    fun `the first buffer defines the origin so playback starts at zero`() {
        val chunks = record { pacer ->
            pacer.accept(AudioFeed.TARGET_SAMPLE_RATE, 500_000L, FloatArray(AudioFeed.TARGET_SAMPLE_RATE))
            pacer.accept(AudioFeed.TARGET_SAMPLE_RATE, 1_500_000L, FloatArray(8_000))
        }

        assertEquals(listOf(0L, 1_000L), chunks.map { it.positionMs })
    }

    @Test
    fun `the origin is reported in container time so a resumed pass can be offset`() {
        var origin = -1L

        record(onOrigin = { origin = it }) { pacer ->
            pacer.accept(AudioFeed.TARGET_SAMPLE_RATE, 12_500_000L, FloatArray(8_000))
        }

        assertEquals(12_500L, origin)
    }

    private fun record(
        onOrigin: (Long) -> Unit = {},
        block: (FeedPacer) -> Unit,
    ): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        val pacer = FeedPacer(
            AudioFeed.Sink { samples, sampleRate, positionMs -> chunks.add(Chunk(samples, sampleRate, positionMs)) },
            onOrigin,
        )
        block(pacer)
        return chunks
    }

    private fun pcm16Buffer(
        samples: ShortArray,
        channelCount: Int,
        sampleRate: Int,
        presentationTimeUs: Long,
    ) = AudioAdDecoder.PcmBuffer(
        pcm16 = samples,
        floats = null,
        sampleCount = samples.size,
        channelCount = channelCount,
        sampleRate = sampleRate,
        positionMs = 0L,
        presentationTimeUs = presentationTimeUs,
    )

    private data class Chunk(val samples: FloatArray, val sampleRate: Int, val positionMs: Long)
}
