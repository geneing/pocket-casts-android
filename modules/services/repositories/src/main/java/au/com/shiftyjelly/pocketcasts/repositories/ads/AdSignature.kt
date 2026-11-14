package au.com.shiftyjelly.pocketcasts.repositories.ads

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Compact spectral signature of a candidate segment, used to recognise the same creative when it
 * turns up again in later episodes of the same podcast. It is deliberately coarse: repeated
 * creatives survive re-encoding, while unrelated content does not come close.
 */
internal object AdSignature {
    private const val SLICES = 8
    private const val SLICE_MS = 500L
    private const val MAX_LOG_ENERGY = 12f

    val byteSize: Int get() = SLICES * AudioFeatureExtractor.MEL_BAND_COUNT

    fun build(frames: List<AudioFrame>, startTimeMs: Long): ByteArray? {
        val segmentFrames = frames.filter { it.positionMs >= startTimeMs && it.positionMs < startTimeMs + SLICES * SLICE_MS }
        if (segmentFrames.size < SLICES) return null
        val signature = ByteArray(byteSize)
        for (slice in 0 until SLICES) {
            val sliceStart = startTimeMs + slice * SLICE_MS
            val sliceFrames = segmentFrames.filter { it.positionMs in sliceStart until sliceStart + SLICE_MS }
            if (sliceFrames.isEmpty()) return null
            for (band in 0 until AudioFeatureExtractor.MEL_BAND_COUNT) {
                var sum = 0f
                for (frame in sliceFrames) sum += frame.melBands[band]
                val mean = sum / sliceFrames.size
                val quantized = ((mean / MAX_LOG_ENERGY).coerceIn(0f, 1f) * 255f).toInt()
                signature[slice * AudioFeatureExtractor.MEL_BAND_COUNT + band] = quantized.toByte()
            }
        }
        return signature
    }

    fun similarity(a: ByteArray, b: ByteArray): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val size = min(a.size, b.size)
        var meanA = 0.0
        var meanB = 0.0
        for (index in 0 until size) {
            meanA += (a[index].toInt() and 0xFF) / 255.0
            meanB += (b[index].toInt() and 0xFF) / 255.0
        }
        meanA /= size
        meanB /= size
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (index in 0 until size) {
            val left = (a[index].toInt() and 0xFF) / 255.0 - meanA
            val right = (b[index].toInt() and 0xFF) / 255.0 - meanB
            dot += left * right
            normA += left * left
            normB += right * right
        }
        if (normA <= 1e-9 || normB <= 1e-9) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
    }
}
