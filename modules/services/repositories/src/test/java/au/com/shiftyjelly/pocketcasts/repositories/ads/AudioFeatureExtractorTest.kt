package au.com.shiftyjelly.pocketcasts.repositories.ads

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFeatureExtractorTest {
    private val sampleRate = 44_100

    @Test
    fun `a tone produces a centroid near its frequency`() {
        val extractor = AudioFeatureExtractor(sampleRate = sampleRate, channelCount = 2)
        val frames = mutableListOf<AudioFrame>()
        extractor.accept(sineWave(frequency = 1_000.0, seconds = 0.5, amplitude = 0.8), onFrame = frames::add)

        assertTrue(frames.isNotEmpty())
        val centroid = frames.map { it.spectralCentroidHz }.average()
        assertTrue("centroid was $centroid", centroid in 900.0..1_100.0)
    }

    @Test
    fun `silence is reported far below speech level`() {
        val extractor = AudioFeatureExtractor(sampleRate = sampleRate, channelCount = 1)
        val frames = mutableListOf<AudioFrame>()
        extractor.accept(ShortArray(sampleRate / 2), onFrame = frames::add)

        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { it.rmsDb < -60f })
    }

    @Test
    fun `identical channels correlate positively and inverted ones negatively`() {
        val identical = AudioFeatureExtractor(sampleRate = sampleRate, channelCount = 2)
        val identicalFrames = mutableListOf<AudioFrame>()
        identical.accept(sineWave(frequency = 440.0, seconds = 0.3, amplitude = 0.5), onFrame = identicalFrames::add)

        val inverted = AudioFeatureExtractor(sampleRate = sampleRate, channelCount = 2)
        val invertedFrames = mutableListOf<AudioFrame>()
        inverted.accept(sineWave(frequency = 440.0, seconds = 0.3, amplitude = 0.5, invertedRight = true), onFrame = invertedFrames::add)

        assertTrue(identicalFrames.isNotEmpty())
        assertTrue(invertedFrames.isNotEmpty())
        assertTrue(identicalFrames.map { it.channelCorrelation }.average() > 0.9)
        assertTrue(invertedFrames.map { it.channelCorrelation }.average() < -0.9)
    }

    @Test
    fun `positions advance with the sample count across chunks`() {
        val extractor = AudioFeatureExtractor(sampleRate = sampleRate, channelCount = 1)
        val frames = mutableListOf<AudioFrame>()
        val chunk = ShortArray(sampleRate / 10)
        repeat(5) { extractor.accept(chunk, onFrame = frames::add) }

        assertTrue(frames.isNotEmpty())
        assertTrue(abs(frames.first().positionMs) <= 200L)
        assertTrue(frames.last().positionMs > 400L)
    }

    private fun sineWave(
        frequency: Double,
        seconds: Double,
        amplitude: Double,
        invertedRight: Boolean = false,
    ): ShortArray {
        val samples = (sampleRate * seconds).toInt()
        val result = ShortArray(samples * 2)
        for (index in 0 until samples) {
            val value = (sin(2 * PI * frequency * index / sampleRate) * amplitude * Short.MAX_VALUE).toInt()
            result[index * 2] = value.toShort()
            result[index * 2 + 1] = if (invertedRight) (-value).toShort() else value.toShort()
        }
        return result
    }
}
