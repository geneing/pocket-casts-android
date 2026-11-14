package au.com.shiftyjelly.pocketcasts.repositories.ads

/**
 * Iterative radix-2 Cooley-Tukey FFT. Sized once and reused for every analysis frame, so the
 * twiddle tables and bit-reversal permutation are not rebuilt per call.
 */
internal class Fft(private val size: Int) {
    init {
        require(size > 0 && size and (size - 1) == 0) { "FFT size must be a power of two, was $size" }
    }

    private val cosTable = DoubleArray(size / 2) { kotlin.math.cos(-2.0 * Math.PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { kotlin.math.sin(-2.0 * Math.PI * it / size) }
    private val bitReverse = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            var reversed = 0
            for (bit in 0 until bits) {
                if ((i and (1 shl bit)) != 0) {
                    reversed = reversed or (1 shl (bits - 1 - bit))
                }
            }
            table[i] = reversed
        }
    }

    /** In-place transform. [imag] must be the same size as [real]. */
    fun transform(real: DoubleArray, imag: DoubleArray) {
        for (i in 0 until size) {
            val j = bitReverse[i]
            if (j > i) {
                val realSwap = real[i]
                real[i] = real[j]
                real[j] = realSwap
                val imagSwap = imag[i]
                imag[i] = imag[j]
                imag[j] = imagSwap
            }
        }

        var length = 2
        while (length <= size) {
            val half = length / 2
            val tableStep = size / length
            var start = 0
            while (start < size) {
                var k = 0
                var tableIndex = 0
                while (k < half) {
                    val cosValue = cosTable[tableIndex]
                    val sinValue = sinTable[tableIndex]
                    val evenIndex = start + k
                    val oddIndex = evenIndex + half
                    val oddReal = real[oddIndex] * cosValue - imag[oddIndex] * sinValue
                    val oddImag = real[oddIndex] * sinValue + imag[oddIndex] * cosValue
                    real[oddIndex] = real[evenIndex] - oddReal
                    imag[oddIndex] = imag[evenIndex] - oddImag
                    real[evenIndex] += oddReal
                    imag[evenIndex] += oddImag
                    k++
                    tableIndex += tableStep
                }
                start += length
            }
            length *= 2
        }
    }
}
