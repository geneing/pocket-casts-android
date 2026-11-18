package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert

/**
 * Windows a decoded feed into the fixed 5 s blocks the recogniser needs.
 *
 * [au.com.shiftyjelly.pocketcasts.repositories.ads.text.AudioFeed] hands over whatever the decoder
 * produced, which is neither 5 s long nor aligned to anything; this class is the only place that
 * knows the model's window, and it keeps the last 2 s so the next window can start with them. A
 * window is emitted with its start in pass time, because the tokens that come back are relative to
 * it.
 */
internal class ParakeetWindowBuffer(
    private val onWindow: (startMs: Long, samples: FloatArray) -> Unit,
) {
    private val window = FloatArray(ParakeetMel.WINDOW_SAMPLES)

    /** How far the window moves each time: 3 s of the 5 s it covers is new audio. */
    private val strideSamples = (STRIDE_MS * ParakeetMel.SAMPLE_RATE / 1000L).toInt()

    /** What is kept from a window that has been transcribed: its last 2 s. */
    private val retainedSamples = window.size - strideSamples
    private var filled = 0
    private var emitted = 0

    /** Adds [samples], emitting every window that is now complete. */
    fun accept(samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(samples.size - offset, window.size - filled)
            samples.copyInto(window, destinationOffset = filled, startIndex = offset, endIndex = offset + count)
            filled += count
            offset += count
            if (filled == window.size) emit()
        }
    }

    /**
     * Emits the last window, zero padded, when there is audio beyond the overlap the previous window
     * already covered. A pass shorter than one window still gets its only window here.
     */
    fun finish() {
        if (filled == 0) return
        if (emitted > 0 && filled <= retainedSamples) return
        for (index in filled until window.size) window[index] = 0f
        onWindow(emitted * STRIDE_MS, window.copyOf())
        filled = 0
    }

    private fun emit() {
        onWindow(emitted * STRIDE_MS, window.copyOf())
        emitted++
        window.copyInto(window, destinationOffset = 0, startIndex = strideSamples, endIndex = window.size)
        filled = retainedSamples
    }

    companion object {
        /** The window's hop in time: 5 s blocks with 2 s of them repeated. */
        const val STRIDE_MS = 3_000L
    }
}
