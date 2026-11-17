package au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine

import android.media.MediaExtractor
import au.com.shiftyjelly.pocketcasts.repositories.ads.AudioAdDecoder
import java.io.File
import timber.log.Timber

/**
 * Feeds an episode's audio to the recognizer as mono floats at the file's own sample rate.
 *
 * A downloaded file and a fetched stream are handed in as a local file, so both go through the
 * same [MediaExtractor] + [AudioAdDecoder] loop; nothing downstream knows which one it got.
 *
 * The sample rate is deliberately **not** forced to 16 kHz: the library resamples every buffer to
 * 16 kHz internally (`add_to_new_audio_buffer`), so decoding at the native 44.1/48 kHz keeps one
 * resampler in the path instead of two.
 *
 * Positions come from the audio actually fed, not from the container: if a buffer goes missing
 * (codec error, format change) the container clock runs ahead, and the feed pads silence for the
 * difference so every later timestamp stays on the episode's timeline.
 */
internal object MoonshineAudioFeed {
    /** Receives mono audio in native-rate chunks, stamped with its absolute episode position. */
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
            Timber.tag(TAG).w(e, "Could not open %s for speech recognition", file.name)
            Result(completed = false, audioMs = 0L, sampleRate = 0, buffers = 0, paddedMs = 0L, stopReason = "open_failed")
        } finally {
            runCatching { extractor.release() }
        }
    }

    fun decode(file: File, startPositionMs: Long, isActive: () -> Boolean, sink: Sink): Result = decode(file, startPositionMs = startPositionMs, isActive = isActive, onOrigin = {}, sink = sink)

    fun decode(extractor: MediaExtractor, isActive: () -> Boolean, sink: Sink): Result = decode(extractor, startPositionMs = 0L, isActive = isActive, sink = sink)

    fun decode(extractor: MediaExtractor, startPositionMs: Long, isActive: () -> Boolean, sink: Sink): Result = decode(extractor, startPositionMs = startPositionMs, isActive = isActive, onOrigin = {}, sink = sink)

    fun decode(
        extractor: MediaExtractor,
        startPositionMs: Long,
        isActive: () -> Boolean,
        onOrigin: (Long) -> Unit,
        sink: Sink,
    ): Result {
        val pacer = FeedPacer(sink, onOrigin)
        val decoded = AudioAdDecoder.decodeBuffers(extractor, startPositionMs = startPositionMs, isActive = isActive) { buffer ->
            pacer.accept(buffer)
        }
        val audioMs = pacer.fedMs
        val paddedMs = pacer.paddedMs
        Timber.tag(TAG).d(
            "Fed %dms of %dHz audio in %d buffers (%dms padded) from %dms: %s",
            audioMs,
            pacer.sampleRate,
            decoded.buffers,
            paddedMs,
            pacer.originMs,
            decoded.stopReason,
        )
        return Result(
            completed = decoded.completed,
            audioMs = audioMs,
            sampleRate = pacer.sampleRate.takeIf { it > 0 } ?: decoded.sampleRate,
            buffers = decoded.buffers,
            paddedMs = paddedMs,
            stopReason = decoded.stopReason,
            originMs = pacer.originMs,
        )
    }

    private const val TAG = "MoonshineStt"
}

/**
 * Turns decoded buffers into mono chunks whose position never drifts from the audio the recognizer
 * heard. Pure apart from its sink, so the padding rule is testable without a codec.
 */
internal class FeedPacer(
    private val sink: MoonshineAudioFeed.Sink,
    private val onOrigin: (Long) -> Unit = {},
) {
    var sampleRate: Int = 0
        private set

    /** Audio fed so far, in frames at [sampleRate]. */
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
        if (sampleRate != this.sampleRate) {
            // A decoder format change keeps the elapsed time and re-expresses it in the new rate,
            // so a rate switch cannot pull every later timestamp back to zero.
            val elapsedMs = fedMs
            this.sampleRate = sampleRate
            fedFrames = elapsedMs * sampleRate / 1000L
        }
        if (originUs < 0) {
            originUs = presentationTimeUs.coerceAtLeast(0L)
            originMs = originUs / 1000L
            onOrigin(originMs)
        }

        val expectedFrames = if (presentationTimeUs >= 0) {
            (presentationTimeUs - originUs) * sampleRate / MICROS_PER_SECOND
        } else {
            fedFrames
        }
        val driftFrames = expectedFrames - fedFrames
        if (driftFrames > toleranceFrames(sampleRate)) {
            // A corrupt timestamp must not allocate a huge buffer; past the cap the pass is better
            // off slightly shifted than out of memory.
            val padFrames = minOf(driftFrames, MAX_PAD_MS * sampleRate / 1000L)
            sink.onAudio(FloatArray(padFrames.toInt()), sampleRate, fedMs)
            fedFrames += padFrames
            fedMs = framesToMs(fedFrames, sampleRate)
            paddedMs += padFrames * 1000L / sampleRate
        }
        sink.onAudio(mono, sampleRate, fedMs)
        fedFrames += mono.size
        fedMs = framesToMs(fedFrames, sampleRate)
    }

    private fun toleranceFrames(sampleRate: Int) = DRIFT_TOLERANCE_MS * sampleRate / 1000L

    private fun framesToMs(frames: Long, sampleRate: Int): Long = frames * 1000L / sampleRate

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
