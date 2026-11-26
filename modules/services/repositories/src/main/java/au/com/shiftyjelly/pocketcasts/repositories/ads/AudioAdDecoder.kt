package au.com.shiftyjelly.pocketcasts.repositories.ads

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import timber.log.Timber

/**
 * Decodes an audio track to PCM and hands sequential buffers to a sink. Used by the offline
 * analysis passes: the energy profiler wants PCM16, the speech recognizer wants floats, and both
 * need the same extractor/codec loop so the two never disagree about the audio.
 */
internal object AudioAdDecoder {
    private const val TAG = "AdAnalysis"
    private const val PROGRESS_STEP_MS = 30_000L

    /** How many samples are queued per wait for output; the whole input queue of every codec fits. */
    private const val MAX_INPUT_BATCH = 8

    data class Result(
        val completed: Boolean,
        val lastPositionMs: Long,
        val decodedMs: Long = 0,
        val buffers: Int = 0,
        val sampleRate: Int = 0,
        val channelCount: Int = 0,
        val wallMs: Long = 0,
        val stopReason: String = "not_started",
    )

    /**
     * One decoded buffer in the format the codec produced: either interleaved PCM16 or interleaved
     * floats, never both. [positionMs] comes from the decoded sample count (what the listener
     * actually heard), while [presentationTimeUs] is the container timestamp of the same buffer and
     * is the only way to notice that a buffer went missing.
     */
    class PcmBuffer internal constructor(
        val pcm16: ShortArray?,
        val floats: FloatArray?,
        val sampleCount: Int,
        val channelCount: Int,
        val sampleRate: Int,
        val positionMs: Long,
        val presentationTimeUs: Long,
    )

    /**
     * Decodes from [startPositionMs] and calls [onPcm] with interleaved PCM16 and the decoder
     * timestamp of the first buffer, so features can be stamped with media-time positions.
     */
    fun decode(
        extractor: MediaExtractor,
        startPositionMs: Long,
        isActive: () -> Boolean,
        onPcm: (samples: ShortArray, sampleCount: Int, channelCount: Int, sampleRate: Int, positionMs: Long) -> Unit,
    ): Result = decodeBuffers(extractor, startPositionMs, isActive) { buffer ->
        val samples = buffer.pcm16 ?: buffer.floats?.let(::floatsToPcm16) ?: return@decodeBuffers
        onPcm(samples, buffer.sampleCount, buffer.channelCount, buffer.sampleRate, buffer.positionMs)
    }

    /** Decodes from [startPositionMs] and calls [onBuffer] with every buffer the codec produced. */
    fun decodeBuffers(
        extractor: MediaExtractor,
        startPositionMs: Long,
        isActive: () -> Boolean,
        onBuffer: (PcmBuffer) -> Unit,
    ): Result {
        val startedAt = System.currentTimeMillis()
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            Timber.tag(TAG).w("No audio track among ${extractor.trackCount} tracks, cannot analyse")
            return Result(completed = false, lastPositionMs = startPositionMs, stopReason = "no_audio_track")
        }

        extractor.selectTrack(trackIndex)
        if (startPositionMs > 0) {
            extractor.seekTo(startPositionMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }

        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: return Result(completed = false, lastPositionMs = startPositionMs, stopReason = "no_mime")
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val declaredDurationMs = inputFormat.getLong(MediaFormat.KEY_DURATION, -1L) / 1000L
        Timber.tag(TAG).d(
            "Decoding %s track %d/%d: %dHz x%d, declared=%dms, seekTo=%dms",
            mime,
            trackIndex,
            extractor.trackCount,
            sampleRate,
            channelCount,
            declaredDurationMs,
            startPositionMs,
        )

        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var isFloatOutput = false
            var anchorUs = -1L
            var samplesSinceAnchor = 0L
            var lastPositionMs = startPositionMs
            var completed = false
            var buffers = 0
            var nextProgressMs = startPositionMs + PROGRESS_STEP_MS

            while (!outputDone && isActive()) {
                // Fill the decoder before waiting for it: hand it every sample it will take without
                // blocking, then wait for the output that batch produces. One sample per round trip
                // cost 10ms of waiting on each ~26ms buffer of a 44.1 kHz mp3, which held the whole
                // decode to 1.6x realtime; a batch is what turns the waits into actual decoding.
                var queued = 0
                while (!inputDone && queued < MAX_INPUT_BATCH) {
                    val inputIndex = codec.dequeueInputBuffer(0)
                    if (inputIndex < 0) break
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    val size = if (inputBuffer == null) -1 else extractor.readSampleData(inputBuffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                        Timber.tag(TAG).d("Input exhausted at %dms", lastPositionMs)
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                        queued++
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloatOutput = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                        Timber.tag(TAG).d(
                            "Decoder output format: %dHz x%d float=%s",
                            sampleRate,
                            channelCount,
                            isFloatOutput,
                        )
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> {
                        if (outputIndex >= 0) {
                            val buffer = if (bufferInfo.size > 0 && channelCount > 0 && sampleRate > 0) {
                                readBuffer(codec, outputIndex, bufferInfo, isFloatOutput, channelCount, sampleRate)
                            } else {
                                null
                            }
                            if (buffer != null) {
                                if (anchorUs < 0 && bufferInfo.presentationTimeUs >= 0) {
                                    anchorUs = bufferInfo.presentationTimeUs
                                    Timber.tag(TAG).d("Decoder anchor at %dms", anchorUs / 1000L)
                                }
                                val positionMs = if (anchorUs >= 0) {
                                    anchorUs / 1000L + samplesSinceAnchor * 1000L / sampleRate
                                } else {
                                    lastPositionMs
                                }
                                onBuffer(buffer.withPosition(positionMs))
                                samplesSinceAnchor += buffer.sampleCount / channelCount
                                lastPositionMs = positionMs + buffer.sampleCount / channelCount * 1000L / sampleRate
                                buffers++
                                if (positionMs >= nextProgressMs) {
                                    nextProgressMs = positionMs + PROGRESS_STEP_MS
                                    val audioMs = samplesSinceAnchor * 1000L / sampleRate
                                    val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                                    Timber.tag(TAG).d(
                                        "Decoded %dms (%d buffers) in %dms, %.1fx realtime",
                                        positionMs,
                                        buffers,
                                        elapsedMs,
                                        audioMs.toFloat() / elapsedMs,
                                    )
                                }
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                                completed = true
                            }
                        }
                    }
                }
            }
            val wallMs = System.currentTimeMillis() - startedAt
            val stopReason = when {
                completed -> "end_of_stream"
                !isActive() -> "stopped_by_caller"
                else -> "output_done"
            }
            val audioMs = samplesSinceAnchor * 1000L / sampleRate.coerceAtLeast(1)
            Timber.tag(TAG).d(
                "Decode stopped (%s) at %dms: %d buffers, %dms audio in %dms wall",
                stopReason,
                lastPositionMs,
                buffers,
                audioMs,
                wallMs,
            )
            return Result(
                completed = completed,
                lastPositionMs = lastPositionMs,
                decodedMs = audioMs,
                buffers = buffers,
                sampleRate = sampleRate,
                channelCount = channelCount,
                wallMs = wallMs,
                stopReason = stopReason,
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Audio decode failed")
            return Result(
                completed = false,
                lastPositionMs = startPositionMs,
                wallMs = System.currentTimeMillis() - startedAt,
                stopReason = "error",
            )
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun readBuffer(
        codec: MediaCodec,
        outputIndex: Int,
        bufferInfo: MediaCodec.BufferInfo,
        isFloatOutput: Boolean,
        channelCount: Int,
        sampleRate: Int,
    ): PcmBuffer? {
        val buffer = codec.getOutputBuffer(outputIndex) ?: return null
        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)
        return if (isFloatOutput) {
            val floats = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val samples = FloatArray(floats.remaining()).also { floats.get(it) }
            PcmBuffer(
                pcm16 = null,
                floats = samples,
                sampleCount = samples.size,
                channelCount = channelCount,
                sampleRate = sampleRate,
                positionMs = 0L,
                presentationTimeUs = bufferInfo.presentationTimeUs,
            )
        } else {
            val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples = ShortArray(shorts.remaining()).also { shorts.get(it) }
            PcmBuffer(
                pcm16 = samples,
                floats = null,
                sampleCount = samples.size,
                channelCount = channelCount,
                sampleRate = sampleRate,
                positionMs = 0L,
                presentationTimeUs = bufferInfo.presentationTimeUs,
            )
        }
    }

    private fun floatsToPcm16(floats: FloatArray): ShortArray = ShortArray(floats.size) { index ->
        (floats[index] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
    }

    private fun PcmBuffer.withPosition(positionMs: Long) = PcmBuffer(
        pcm16 = pcm16,
        floats = floats,
        sampleCount = sampleCount,
        channelCount = channelCount,
        sampleRate = sampleRate,
        positionMs = positionMs,
        presentationTimeUs = presentationTimeUs,
    )

    private const val TIMEOUT_US = 10_000L
}
