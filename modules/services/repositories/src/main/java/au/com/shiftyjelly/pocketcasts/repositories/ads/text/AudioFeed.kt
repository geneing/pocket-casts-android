package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.media.MediaExtractor
import au.com.shiftyjelly.pocketcasts.repositories.ads.AudioAdDecoder
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.ParakeetStats
import java.io.File
import timber.log.Timber

/**
 * Feeds an episode's audio to the recognizer as mono floats at a constant [TARGET_SAMPLE_RATE].
 *
 * A downloaded file and a fetched stream are handed in as a local file, so both go through the
 * same [MediaExtractor] + [AudioAdDecoder] loop; nothing downstream knows which one it got.
 *
 * The resampling happens here rather than inside the recognizer for two reasons. First, it keeps one
 * resampler in the path instead of two. Second - the one that decides it - the mel front end is
 * built for one rate and the model's 5 s windows are counted in samples, so a decoder that changed
 * its output rate mid-file (44.1 kHz to 48 kHz on a concatenated file, a format change in the middle
 * of a VBR one) would either be resampled twice or silently change the number of samples a window
 * holds. Resampling every buffer makes a rate change invisible: everything downstream only ever sees
 * [TARGET_SAMPLE_RATE].
 *
 * Positions come from the audio actually fed, not from the container: if a buffer goes missing
 * (codec error, format change) the container clock runs ahead, and the feed pads silence for the
 * difference so every later timestamp stays on the episode's timeline. The padding is inserted after
 * resampling, in the same 16 kHz clock the sink and the recognizer use.
 */
internal object AudioFeed {
    /** The rate every chunk is delivered at, and the rate the transcriber is created with. */
    const val TARGET_SAMPLE_RATE = 16_000

    /** Receives mono audio in [TARGET_SAMPLE_RATE] chunks, stamped with its position in the pass. */
    fun interface Sink {
        fun onAudio(samples: FloatArray, sampleRate: Int, positionMs: Long)
    }

    data class Result(
        val completed: Boolean,
        val audioMs: Long,
        val sampleRate: Int,
        val buffers: Int,
        val paddedMs: Long,
        val stopReason: String,
        /** Container time of the first buffer, the point the recognizer's own clock starts at. */
        val originMs: Long = 0L,
    )

    /** Decodes [file] from its start. The caller owns cancellation through [isActive]. */
    fun decode(file: File, isActive: () -> Boolean, sink: Sink): Result = decode(file, startPositionMs = 0L, isActive = isActive, sink = sink)

    /** Decodes [file] from [startPositionMs]; the keyframe may start earlier, see [Result.originMs]. */
    fun decode(file: File, startPositionMs: Long, isActive: () -> Boolean, sink: Sink): Result = decode(file, startPositionMs = startPositionMs, isActive = isActive, onOrigin = {}, sink = sink)

    /**
     * Decodes [file] from [startPositionMs] and reports through [onOrigin] the container time the
     * recognizer's clock starts at. The seek lands on a keyframe, so that origin is usually a little
     * before [startPositionMs]; timestamps taken from the recognizer are relative to it, not to the
     * resume point.
     */
    fun decode(
        file: File,
        startPositionMs: Long,
        isActive: () -> Boolean,
        onOrigin: (Long) -> Unit,
        sink: Sink,
    ): Result {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            decode(extractor, startPositionMs, isActive, onOrigin, sink)
        } catch (e: Exception) {
            Timber.tag(ParakeetStats.TAG).w(e, "Could not open %s for speech recognition", file.name)
            Result(
                completed = false,
                audioMs = 0L,
                sampleRate = 0,
                buffers = 0,
                paddedMs = 0L,
                stopReason = "open_failed",
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun decode(
        extractor: MediaExtractor,
        startPositionMs: Long,
        isActive: () -> Boolean,
        onOrigin: (Long) -> Unit,
        sink: Sink,
    ): Result {
        val pacer = FeedPacer(sink, onOrigin)
        val decoded = AudioAdDecoder.decodeBuffers(
            extractor,
            startPositionMs = startPositionMs,
            isActive = isActive,
        ) { buffer ->
            pacer.accept(buffer)
        }
        // The same tag as the pass it feeds: `logcat -s ParakeetStt` then shows the decode clock
        // next to the recogniser's, which is how a feed that stalls is told from a model that does.
        Timber.tag(ParakeetStats.TAG).d(
            "Fed %dms of %dHz audio in %d buffers (%dms padded) from %dms: %s",
            pacer.fedMs,
            TARGET_SAMPLE_RATE,
            decoded.buffers,
            pacer.paddedMs,
            pacer.originMs,
            decoded.stopReason,
        )
        return Result(
            completed = decoded.completed,
            audioMs = pacer.fedMs,
            sampleRate = TARGET_SAMPLE_RATE,
            buffers = decoded.buffers,
            paddedMs = pacer.paddedMs,
            stopReason = decoded.stopReason,
            originMs = pacer.originMs,
        )
    }
}

/**
 * Turns decoded buffers into 16 kHz mono chunks whose position never drifts from the audio the
 * recognizer heard. Pure apart from its sink, so the resampling and padding rules are testable
 * without a codec.
 */
internal class FeedPacer(
    private val sink: AudioFeed.Sink,
    private val onOrigin: (Long) -> Unit = {},
) {
    /** Audio fed so far, in frames at [AudioFeed.TARGET_SAMPLE_RATE]. */
    var fedMs: Long = 0L
        private set

    /** Total silence inserted so far, in milliseconds. */
    var paddedMs: Long = 0L
        private set

    /** Container time of the first buffer; the recognizer's clock starts here. */
    var originMs: Long = 0L
        private set

    private var fedFrames = 0L
    private var originUs = -1L

    /** The last input sample of the previous buffer, so interpolation is continuous across seams. */
    private var carry = 0f
    private var hasCarry = false

    /**
     * True when [carry] was already handed to the sink because that buffer needed no resampling;
     * the next output then starts one output period after it instead of on it.
     */
    private var carryEmitted = false

    /** Where the next output sample lands, in input samples measured from [carry]. */
    private var phase = 0.0

    fun accept(buffer: AudioAdDecoder.PcmBuffer) {
        accept(buffer.sampleRate, buffer.presentationTimeUs, buffer.monoFloats())
    }

    /**
     * Feeds one chunk positioned at [presentationTimeUs] in the container.
     *
     * The first chunk defines the origin, so playback always starts at 0 even when the container's
     * first sample is not (encoder delay). When the container is more than [DRIFT_TOLERANCE_MS]
     * ahead of what was fed, the difference is padded with silence: shorter gaps are left alone
     * because they are inside the accuracy of the cues we store, and padding each one would insert
     * clicks into the audio for no benefit.
     */
    fun accept(sampleRate: Int, presentationTimeUs: Long, mono: FloatArray) {
        if (sampleRate <= 0 || mono.isEmpty()) return
        if (originUs < 0) {
            originUs = presentationTimeUs.coerceAtLeast(0L)
            originMs = originUs / 1000L
            onOrigin(originMs)
        }

        // Resampling happens before the padding so the silence is inserted in the clock the sink
        // and the recognizer share. A rate change only changes the step: the output clock keeps
        // counting from zero, so a switch cannot pull every later timestamp back to the start.
        val resampled = resample(mono, sampleRate)

        val expectedFrames = if (presentationTimeUs >= 0) {
            (presentationTimeUs - originUs) * AudioFeed.TARGET_SAMPLE_RATE / MICROS_PER_SECOND
        } else {
            fedFrames
        }
        val driftFrames = expectedFrames - fedFrames
        if (driftFrames > toleranceFrames()) {
            // A corrupt timestamp must not allocate a huge buffer; past the cap the pass is better
            // off slightly shifted than out of memory.
            val padFrames = minOf(driftFrames, MAX_PAD_MS * AudioFeed.TARGET_SAMPLE_RATE / 1000L)
            sink.onAudio(FloatArray(padFrames.toInt()), AudioFeed.TARGET_SAMPLE_RATE, fedMs)
            fedFrames += padFrames
            fedMs = framesToMs(fedFrames)
            paddedMs += padFrames * 1000L / AudioFeed.TARGET_SAMPLE_RATE
        }
        if (resampled.isEmpty()) return
        sink.onAudio(resampled, AudioFeed.TARGET_SAMPLE_RATE, fedMs)
        fedFrames += resampled.size
        fedMs = framesToMs(fedFrames)
    }

    /**
     * Linear interpolation from [inputRate] to [AudioFeed.TARGET_SAMPLE_RATE].
     *
     * The last input sample and the fractional position of the next output sample are carried
     * across calls, so the seam between two buffers neither drops nor repeats a sample and the
     * output count tracks the input count exactly over a long file. A mid-file rate change keeps
     * the carried phase; it is at most one input sample, which is a rounding error at these rates.
     */
    private fun resample(mono: FloatArray, inputRate: Int): FloatArray {
        if (inputRate == AudioFeed.TARGET_SAMPLE_RATE) {
            // Already what the recognizer wants; only the carry has to follow so a later rate
            // change still interpolates across this seam.
            carry = mono.last()
            hasCarry = true
            carryEmitted = true
            phase = 0.0
            return mono
        }

        val history: FloatArray
        if (hasCarry) {
            history = FloatArray(mono.size + 1)
            history[0] = carry
            mono.copyInto(history, destinationOffset = 1)
        } else {
            // The first buffer has nothing to interpolate from; index 0 is the buffer's own first
            // sample, so output sample 0 is exactly the audio the decoder produced.
            history = mono
        }

        val step = inputRate.toDouble() / AudioFeed.TARGET_SAMPLE_RATE
        val lastIndex = history.size - 1
        // A carry that was already emitted is one output period behind where the next sample goes;
        // one that was not is simply where interpolation left off.
        val startPosition = if (carryEmitted) step else phase
        carryEmitted = false
        var position = startPosition
        var count = 0
        while (position < lastIndex) {
            count++
            position += step
        }

        val output = FloatArray(count)
        position = startPosition
        for (index in 0 until count) {
            val left = position.toInt()
            val fraction = (position - left).toFloat()
            val before = history[left]
            output[index] = before + (history[left + 1] - before) * fraction
            position += step
        }

        carry = history[lastIndex]
        hasCarry = true
        phase = position - lastIndex
        return output
    }

    private fun toleranceFrames() = DRIFT_TOLERANCE_MS * AudioFeed.TARGET_SAMPLE_RATE / 1000L

    private fun framesToMs(frames: Long): Long = frames * 1000L / AudioFeed.TARGET_SAMPLE_RATE

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
        const val DRIFT_TOLERANCE_MS = 250L
        const val MAX_PAD_MS = 10_000L
    }
}

/** One decoder buffer as mono floats in [-1, 1), at the buffer's own sample rate. */
internal fun AudioAdDecoder.PcmBuffer.monoFloats(): FloatArray {
    val interleaved = floats ?: pcm16ToFloat(pcm16 ?: ShortArray(0), sampleCount)
    return toMono(interleaved, sampleCount, channelCount)
}

/** Interleaved PCM16 scaled to [-1, 1). */
internal fun pcm16ToFloat(samples: ShortArray, count: Int): FloatArray {
    val size = minOf(count, samples.size)
    return FloatArray(size) { index -> samples[index] / PCM16_FULL_SCALE }
}

/** Interleaved samples averaged down to one channel. */
internal fun toMono(samples: FloatArray, count: Int, channelCount: Int): FloatArray {
    val size = minOf(count, samples.size)
    if (channelCount <= 1) return if (size == samples.size) samples else samples.copyOf(size)
    val frames = size / channelCount
    return FloatArray(frames) { frame ->
        var sum = 0f
        for (channel in 0 until channelCount) sum += samples[frame * channelCount + channel]
        sum / channelCount
    }
}

/** 32768, the magnitude of the most negative PCM16 sample, so the scale is symmetric. */
private const val PCM16_FULL_SCALE = 32768f
