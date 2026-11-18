package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert

/**
 * Windows a decoded feed into the fixed blocks the recogniser needs.
 *
 * [au.com.shiftyjelly.pocketcasts.repositories.ads.text.AudioFeed] hands over whatever the decoder
 * produced, which is neither one window long nor aligned to anything; this class is the only place
 * that knows the model's window, and it keeps the overlap so the next window can start with it. A
 * window is emitted with its start in pass time, because the tokens that come back are relative to
 * it.
 *
 * The Redux encoder holds 10 s, so a window is 10 s and the next one starts 8 s later: 2 s of every
 * window are the tail of the previous one, which is what lets [ParakeetWords] settle the seam.
 */
internal class ParakeetWindowBuffer(
    windowSamples: Int = WINDOW_SAMPLES,
    overlapMs: Long = OVERLAP_MS,
    private val onWindow: (startMs: Long, samples: FloatArray) -> Unit,
) {
    private val window = FloatArray(windowSamples)

    /** How far the window moves each time: 8 s of the 10 s it covers is new audio. */
    private val strideSamples = windowSamples - (overlapMs * SAMPLE_RATE / 1000L).toInt()

    /** What is kept from a window that has been transcribed: its last 2 s. */
    private val retainedSamples = windowSamples - strideSamples
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
     * already covered. A pass shorter than one window still gets its only window here, unless it is
     * too short for the mel graph to produce a frame, which is the model's own minimum.
     */
    fun finish() {
        if (filled < MIN_WINDOW_SAMPLES) return
        if (emitted > 0 && filled <= retainedSamples) return
        for (index in filled until window.size) window[index] = 0f
        onWindow(emitted * (strideSamples * 1000L / SAMPLE_RATE), window.copyOf())
        filled = 0
    }

    private fun emit() {
        onWindow(emitted * (strideSamples * 1000L / SAMPLE_RATE), window.copyOf())
        emitted++
        window.copyInto(window, destinationOffset = 0, startIndex = strideSamples, endIndex = window.size)
        filled = retainedSamples
    }

    companion object {
        /** The rate [AudioFeed] decodes to, and the one every graph expects. */
        const val SAMPLE_RATE = 16_000

        /** The 10 s window of the Redux export. */
        const val WINDOW_MS = 10_000L
        const val WINDOW_SAMPLES = (WINDOW_MS * SAMPLE_RATE / 1000L).toInt()

        /** How much of each window is repeated in the next: the seam [ParakeetWords] merges. */
        const val OVERLAP_MS = 2_000L

        /** Below two mel frames the model can say nothing, so a sliver is not a window. */
        const val MIN_WINDOW_SAMPLES = 320
    }
}
