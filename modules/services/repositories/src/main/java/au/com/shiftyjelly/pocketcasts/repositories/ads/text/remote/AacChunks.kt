package au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.AudioFeed
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Encodes a slice of the episode as mono AAC in an m4a, for audio that cannot be cut as it is.
 *
 * This is the fallback: a pass copies the episode's own compressed frames instead ([AudioSplitter]),
 * and only a WAV, an unknown container, or a codec the muxer cannot write reaches this encoder. What
 * it does is turn raw audio into something small enough to send — the audio arrives from [AudioFeed]
 * mono at 16 kHz, which is the rate the encoder is configured with, so no resampling happens twice.
 * At 32 kbit/s the upload is 4 kB/s of audio: a ten minute chunk is 2.4 MB against 9.6 MB of WAV,
 * and OpenRouter refuses a request body over 50 MiB, which a WAV chunk would approach after twenty
 * minutes. The provider transcribes 32 kbit/s mono and WAV identically, so the compression costs
 * nothing in recognition.
 *
 * AAC is what the platform can encode: Android ships an AAC encoder and has no MP3 one, so an mp3
 * chunk would need a native LAME build for the same bitrate. The endpoint accepts both, and the
 * measurements are in `dev/adaware/remote-asr/README.md`.
 */
internal object AacChunks {
    /** The `input_audio.format` this chunk is sent as. */
    const val FORMAT = "m4a"

    private const val SAMPLE_RATE = AudioFeed.TARGET_SAMPLE_RATE
    private const val CHANNELS = 1
    private const val BIT_RATE = 32_000
    private const val TIMEOUT_US = 10_000L
    private const val SAMPLES_PER_INPUT = 4_096
    private const val END_OF_STREAM_POLLS = 5
    private const val MAX_DRAIN_POLLS = 10_000

    /** How many input buffers are queued per wait for output; more than any encoder queue holds. */
    private const val MAX_INPUT_BATCH = 8

    /** How many empty input polls (10 ms apart) mean the encoder is wedged rather than busy. */
    private const val MAX_INPUT_STALLS = 300

    /**
     * Encodes the episode audio between [fromMs] and [untilMs] into [target].
     *
     * Returns null when the slice produced no audio, or when the device's encoder failed; the
     * caller reports the failure rather than sending an empty request. `isActive` is consulted
     * between decoded buffers, so a cancelled pass stops encoding within a buffer.
     */
    fun encode(file: File, fromMs: Long, untilMs: Long, target: File, isActive: () -> Boolean): ChunkAudio? {
        if (target.exists() && !target.delete()) {
            LogBuffer.w(LOG_TAG, "Could not clear the previous chunk at ${target.absolutePath}")
        }
        target.parentFile?.mkdirs()

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLES_PER_INPUT * BYTES_PER_SAMPLE)
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var feedWallMs = 0L
        try {
            val encoderStartedAt = SystemClock.elapsedRealtime()
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            codec = encoder
            val sink = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = sink

            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var presentationUs = 0L
            var frames = 0L

            fun drain(endOfStream: Boolean) {
                // The end-of-stream pass must terminate even if the encoder never flags it: an
                // unbounded loop here would wedge a worker with no error to report.
                var polls = 0
                while (polls++ < MAX_DRAIN_POLLS) {
                    val index = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            if (polls > MAX_DRAIN_POLLS / 2) return
                        }

                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                trackIndex = sink.addTrack(encoder.outputFormat)
                                sink.start()
                                muxerStarted = true
                            }
                        }

                        index >= 0 -> {
                            val buffer = encoder.getOutputBuffer(index)
                            if (buffer != null && bufferInfo.size > 0 && muxerStarted) {
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                sink.writeSampleData(trackIndex, buffer, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(index, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            fun feed(audio: FloatArray) {
                val feedStartedAt = SystemClock.elapsedRealtime()
                var offset = 0
                var stalls = 0
                while (offset < audio.size) {
                    // Queue every input buffer the encoder will take without blocking, then let the
                    // drain below wait for the output that batch produces. Waiting for one buffer at
                    // a time put a 10ms poll on each 26ms of audio and held the encoder to 0.6x
                    // realtime, which on a two hour episode is an hour spent waiting on a codec that
                    // was never busy.
                    var queued = 0
                    while (offset < audio.size && queued < MAX_INPUT_BATCH) {
                        val index = encoder.dequeueInputBuffer(0)
                        if (index < 0) break
                        val buffer = encoder.getInputBuffer(index) ?: throw IOException("no encoder input buffer")
                        buffer.clear()
                        val count = minOf(audio.size - offset, buffer.capacity() / BYTES_PER_SAMPLE, SAMPLES_PER_INPUT)
                        for (position in 0 until count) {
                            buffer.putShort(pcm16(audio[offset + position]))
                        }
                        encoder.queueInputBuffer(index, 0, count * BYTES_PER_SAMPLE, presentationUs, 0)
                        presentationUs += count * MICROS_PER_SECOND / SAMPLE_RATE
                        frames += count
                        offset += count
                        queued++
                    }
                    if (queued == 0) {
                        // A wedged encoder hands out no input buffer while its output queue fills;
                        // draining is the fix, and if that does not help the pass has to fail rather
                        // than spin here forever, which is a hang no timeout would report.
                        drain(endOfStream = false)
                        if (++stalls > MAX_INPUT_STALLS) throw IOException("the AAC encoder stopped accepting audio")
                    } else {
                        stalls = 0
                    }
                }
                drain(endOfStream = false)
                feedWallMs += SystemClock.elapsedRealtime() - feedStartedAt
            }

            val origin = longArrayOf(0L)
            var fedMs = 0L
            AudioFeed.decode(
                file = file,
                startPositionMs = fromMs,
                isActive = { isActive() && origin[0] + fedMs < untilMs },
                onOrigin = { origin[0] = it },
            ) { samples, _, positionMs ->
                if (origin[0] + positionMs < untilMs) {
                    feed(samples)
                }
                fedMs = positionMs + samples.size * 1000L / SAMPLE_RATE
            }

            // The encoder buffers a frame or two; stopping it without this flush loses the last
            // words of every chunk, which at a chunk seam is exactly the speech the overlap added.
            var queuedEnd = false
            for (attempt in 0 until END_OF_STREAM_POLLS) {
                val index = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (index < 0) continue
                encoder.queueInputBuffer(index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                queuedEnd = true
                break
            }
            if (queuedEnd) drain(endOfStream = true)

            if (frames == 0L || !muxerStarted) return null
            val audioMs = frames * 1000L / SAMPLE_RATE
            val wallMs = SystemClock.elapsedRealtime() - encoderStartedAt
            // The split that matters when a chunk looks slow: how much of it was the source decode
            // and how much the encoder. The two have different fixes, and only this tells them apart.
            LogBuffer.i(
                LOG_TAG,
                "aac: audio=${audioMs}ms from=${fromMs}ms wall=${wallMs}ms " +
                    "feed=${feedWallMs}ms decode=${wallMs - feedWallMs}ms bytes=${target.length()}",
            )
            return ChunkAudio(
                file = target,
                originMs = origin[0],
                audioMs = audioMs,
                format = FORMAT,
                copied = false,
            )
        } catch (e: Exception) {
            LogBuffer.e(LOG_TAG, e, "Could not encode ${file.name} from ${fromMs}ms as AAC")
            runCatching { target.delete() }
            return null
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted.not()) runCatching { target.delete() }
        }
    }

    /** A decoded sample as signed 16-bit PCM, the only thing the encoder accepts. */
    private fun pcm16(sample: Float): Short = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()

    private const val BYTES_PER_SAMPLE = 2
    private const val MICROS_PER_SECOND = 1_000_000L
    private const val LOG_TAG = "RemoteAsr"
}
