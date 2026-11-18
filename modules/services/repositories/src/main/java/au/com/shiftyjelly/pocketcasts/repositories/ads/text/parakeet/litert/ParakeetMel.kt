package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert

import com.jlibrosa.audio.JLibrosa
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The log-mel front end the Parakeet encoder expects.
 *
 * The `.tflite` graphs carry no preprocessor, unlike the `.litertlm` bundle, so the host app has to
 * produce the 128-bin Slaney log-mel features itself. The recipe is Fixed by the export and matches
 * `Speech2TextMetadata` in the bundle: 0.97 pre-emphasis, a 512-point Hann STFT at a 10 ms hop,
 * 128 Slaney-normalised mel bins from 0 to 8 kHz, natural log, then per-feature mean/std
 * normalisation over the window. Drum it differently and the model still returns words, they are
 * just wrong ones, which is why this mirrors the reference implementation step for step instead of
 * being "close enough".
 *
 * One instance is one window: [process] holds no state between calls, so the same object can be used
 * for every window of a pass.
 */
internal class ParakeetMel {
    /** Turns one window of 16 kHz mono samples into the encoder's flat `[128][500]` feature block. */
    fun process(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val audio = preemphasis(samples)
        val mel = generate(audio)
        val validFrames = (samples.size / HOP_LENGTH).coerceIn(1, N_FRAMES)

        val means = mean(mel, validFrames)
        val devs = deviation(mel, means, validFrames)

        val features = FloatArray(N_FRAMES * N_MELS)
        for (bin in mel.indices) {
            val mean = means[bin]
            val deviation = devs[bin] + EPSILON
            for (frame in 0 until N_FRAMES) {
                // Frames past the audio are silence, not something to normalise: a short final
                // window must look like padding to the encoder, as it does in the bundle.
                val value = if (frame < validFrames) (mel[bin][frame] - mean) / deviation else 0.0f
                features[bin * N_FRAMES + frame] = value
            }
        }
        return features
    }

    private fun preemphasis(samples: FloatArray): FloatArray {
        val result = FloatArray(samples.size)
        result[0] = samples[0]
        for (index in 1 until samples.size) {
            result[index] = samples[index] - PREEMPHASIS * samples[index - 1]
        }
        return result
    }

    /** `[mel bin][frame]`, already natural-log scaled, as librosa and jLibrosa both report it. */
    private fun generate(audio: FloatArray): Array<FloatArray> {
        val mel = librosa.generateMelSpectroGram(audio, SAMPLE_RATE, N_FFT, N_MELS, HOP_LENGTH)
        for (bin in mel.indices) {
            for (frame in mel[bin].indices) {
                mel[bin][frame] = ln(mel[bin][frame] + LOG_ZERO_GUARD)
            }
        }
        return mel
    }

    /**
     * Mean over time, in double: a window is 500 frames of a value around -16.6, and summing those in
     * float leaves an error of the same size as the deviation of a silent window, which turns silence
     * into a constant offset instead of zeros.
     */
    private fun mean(mel: Array<FloatArray>, frames: Int): FloatArray {
        val result = FloatArray(mel.size)
        for (bin in mel.indices) {
            var sum = 0.0
            for (frame in 0 until frames) sum += mel[bin][frame]
            result[bin] = (sum / frames).toFloat()
        }
        return result
    }

    /** Sample deviation over time, accumulated in double for the same reason as [mean]. */
    private fun deviation(mel: Array<FloatArray>, mean: FloatArray, frames: Int): FloatArray {
        val result = FloatArray(mel.size)
        for (bin in mel.indices) {
            var squares = 0.0
            for (frame in 0 until frames) {
                val diff = (mel[bin][frame] - mean[bin]).toDouble()
                squares += diff * diff
            }
            result[bin] = sqrt(squares / max(1, frames - 1)).toFloat()
        }
        return result
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** The encoder's window, in samples: the model's 5 s. */
        const val WINDOW_SAMPLES = 5 * SAMPLE_RATE

        private const val N_FFT = 512
        private const val N_MELS = 128
        private const val N_FRAMES = 500
        private const val HOP_LENGTH = 160
        private const val PREEMPHASIS = 0.97f

        /** `2**-24`, what librosa's `power_to_db` uses to keep the log of silence finite. */
        private val LOG_ZERO_GUARD = 2.0f.pow(-24)

        private const val EPSILON = 1e-5f

        private val librosa by lazy { JLibrosa() }
    }
}
