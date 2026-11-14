package au.com.shiftyjelly.pocketcasts.repositories.ads

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns decoded PCM into a fixed-hop stream of cheap acoustic features. The extractor is fed
 * arbitrary chunk sizes and keeps a window across call boundaries, so it works for both the
 * offline file pass and buffers arriving during playback.
 */
internal class AudioFeatureExtractor(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val frameSize: Int = DEFAULT_FRAME_SIZE,
    private val hopSize: Int = DEFAULT_HOP_SIZE,
    private val melBandCount: Int = MEL_BAND_COUNT,
) {
    private val fft = Fft(frameSize)
    private val hann = FloatArray(frameSize) { i ->
        (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (frameSize - 1))).toFloat()
    }
    private val mono = FloatArray(frameSize)
    private val left = FloatArray(frameSize)
    private val right = FloatArray(frameSize)
    private val real = DoubleArray(frameSize)
    private val imag = DoubleArray(frameSize)
    private val power = FloatArray(frameSize / 2 + 1)
    private val melFilters = buildMelFilters(sampleRate, frameSize, melBandCount)
    private val frameMel = FloatArray(melBandCount)

    private var filled = 0
    private var totalSamples = 0L
    private var basePositionMs = 0L
    private var started = false

    /** Position, in milliseconds, of the newest decoded sample. */
    var positionMs: Long = 0L
        private set

    fun reset(positionMs: Long = 0L) {
        filled = 0
        totalSamples = 0
        basePositionMs = positionMs
        started = false
        this.positionMs = positionMs
    }

    /**
     * Feeds interleaved PCM16 samples. [firstChunkPositionMs] is only used on the first call after
     * [reset]; later positions are derived from the sample count so a decoder that omits timestamps
     * on some buffers still produces a continuous timeline.
     */
    fun accept(
        samples: ShortArray,
        sampleCount: Int = samples.size,
        chunkPositionMs: Long? = null,
        onFrame: (AudioFrame) -> Unit,
    ) {
        if (sampleCount <= 0 || channelCount <= 0) return
        if (!started) {
            started = true
            basePositionMs = chunkPositionMs ?: basePositionMs
        }
        var index = 0
        while (index + channelCount <= sampleCount) {
            val leftSample = samples[index]
            val rightSample = if (channelCount > 1) samples[index + 1] else leftSample
            pushSample(leftSample, rightSample)
            totalSamples++
            if (filled == frameSize) {
                onFrame(computeFrame())
                shiftWindow()
            }
            index += channelCount
        }
        positionMs = basePositionMs + totalSamples * 1000L / sampleRate
    }

    private fun pushSample(leftSample: Short, rightSample: Short) {
        val leftValue = leftSample / 32768f
        val rightValue = rightSample / 32768f
        left[filled] = leftValue
        right[filled] = rightValue
        mono[filled] = (leftValue + rightValue) * 0.5f
        filled++
    }

    private fun shiftWindow() {
        val keep = frameSize - hopSize
        System.arraycopy(mono, hopSize, mono, 0, keep)
        System.arraycopy(left, hopSize, left, 0, keep)
        System.arraycopy(right, hopSize, right, 0, keep)
        filled = keep
    }

    private fun computeFrame(): AudioFrame {
        val frameStartMs = basePositionMs + (totalSamples - filled) * 1000L / sampleRate

        var sumSquares = 0.0
        var crossings = 0
        var previousSign = 0
        var leftEnergy = 0.0
        var rightEnergy = 0.0
        var crossEnergy = 0.0
        for (i in 0 until frameSize) {
            val value = mono[i]
            sumSquares += value * value
            val sign = if (value >= 0) 1 else -1
            if (i > 0 && sign != previousSign) crossings++
            previousSign = sign
            leftEnergy += left[i] * left[i]
            rightEnergy += right[i] * right[i]
            crossEnergy += left[i] * right[i]
            real[i] = (value * hann[i]).toDouble()
            imag[i] = 0.0
        }
        val rms = sqrt(sumSquares / frameSize)
        val rmsDb = if (rms <= 0.0) MIN_DB else (20.0 * log10(rms)).toFloat().coerceAtLeast(MIN_DB)

        fft.transform(real, imag)
        var totalPower = 0.0
        var weightedFrequency = 0.0
        var logPowerSum = 0.0
        for (bin in power.indices) {
            val value = (real[bin] * real[bin] + imag[bin] * imag[bin]).toFloat()
            power[bin] = value
            totalPower += value
            weightedFrequency += value * bin.toDouble() * sampleRate / frameSize
            logPowerSum += ln(value + 1e-12)
        }
        val centroid = if (totalPower > 0) (weightedFrequency / totalPower).toFloat() else 0f
        val geometricMean = kotlin.math.exp(logPowerSum / power.size)
        val arithmeticMean = totalPower / power.size
        val flatness = if (arithmeticMean > 0) (geometricMean / arithmeticMean).toFloat().coerceIn(0f, 1f) else 0f

        for (band in 0 until melBandCount) {
            var energy = 0.0
            val filter = melFilters[band]
            for (bin in filter.indices) {
                energy += power[bin] * filter[bin]
            }
            frameMel[band] = ln(1.0 + energy).toFloat()
        }

        val correlation = when {
            channelCount <= 1 -> 1f
            leftEnergy <= 0.0 || rightEnergy <= 0.0 -> 1f
            else -> (crossEnergy / sqrt(leftEnergy * rightEnergy)).toFloat().coerceIn(-1f, 1f)
        }

        return AudioFrame(
            positionMs = frameStartMs,
            rmsDb = rmsDb,
            zeroCrossingRate = crossings.toFloat() / frameSize,
            spectralCentroidHz = centroid,
            spectralFlatness = flatness,
            channelCorrelation = correlation,
            melBands = frameMel.copyOf(),
        )
    }

    companion object {
        const val DEFAULT_FRAME_SIZE = 1024
        const val DEFAULT_HOP_SIZE = 512
        const val MEL_BAND_COUNT = 12
        private const val MIN_DB = -90f

        private fun buildMelFilters(sampleRate: Int, frameSize: Int, bandCount: Int): Array<FloatArray> {
            val binCount = frameSize / 2 + 1
            val maxFrequency = min(8000f, sampleRate / 2f * 0.95f)
            val melMax = 2595.0 * log10(1.0 + maxFrequency / 700.0)
            val melMin = 2595.0 * log10(1.0 + 80.0 / 700.0)
            val edges = FloatArray(bandCount + 2) { i ->
                val mel = melMin + (melMax - melMin) * i / (bandCount + 1)
                (700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)).toFloat()
            }
            val binWidth = sampleRate.toFloat() / frameSize
            return Array(bandCount) { band ->
                val start = edges[band]
                val center = edges[band + 1]
                val end = edges[band + 2]
                FloatArray(binCount) { bin ->
                    val frequency = bin * binWidth
                    when {
                        frequency <= start || frequency >= end -> 0f
                        frequency <= center -> (frequency - start) / (center - start)
                        else -> (end - frequency) / (end - center)
                    }.let { if (abs(it) < 1e-6f) 0f else it }
                }
            }
        }
    }
}
