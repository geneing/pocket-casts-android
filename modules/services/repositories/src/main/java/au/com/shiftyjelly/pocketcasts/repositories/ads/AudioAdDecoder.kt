package au.com.shiftyjelly.pocketcasts.repositories.ads

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import timber.log.Timber

/**
 * Decodes an audio track to PCM16 and hands sequential buffers to [onPcm]. Used for the offline
 * analysis pass; the callback receives the decoder timestamp of the first buffer so features can be
 * stamped with media-time positions.
 */
internal object AudioAdDecoder {
    private const val TAG = "AdAnalysis"
    private const val PROGRESS_STEP_MS = 30_000L

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

    fun decode(
        extractor: MediaExtractor,
        startPositionMs: Long,
        isActive: () -> Boolean,
        onPcm: (samples: ShortArray, sampleCount: Int, channelCount: Int, sampleRate: Int, positionMs: Long) -> Unit,
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
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val size = if (inputBuffer == null) -1 else extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                            Timber.tag(TAG).d("Input exhausted at %dms", lastPositionMs)
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
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
                            if (bufferInfo.size > 0 && channelCount > 0 && sampleRate > 0) {
                                val samples = readSamples(codec, outputIndex, bufferInfo, isFloatOutput)
                                if (samples.isNotEmpty()) {
                                    if (anchorUs < 0 && bufferInfo.presentationTimeUs >= 0) {
                                        anchorUs = bufferInfo.presentationTimeUs
                                        Timber.tag(TAG).d("Decoder anchor at %dms", anchorUs / 1000L)
                                    }
                                    val positionMs = if (anchorUs >= 0) {
                                        anchorUs / 1000L + samplesSinceAnchor * 1000L / sampleRate
                                    } else {
                                        lastPositionMs
                                    }
                                    onPcm(samples, samples.size, channelCount, sampleRate, positionMs)
                                    samplesSinceAnchor += samples.size / channelCount
                                    lastPositionMs = positionMs + samples.size / channelCount * 1000L / sampleRate
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

    private fun readSamples(
        codec: MediaCodec,
        outputIndex: Int,
        bufferInfo: MediaCodec.BufferInfo,
        isFloatOutput: Boolean,
    ): ShortArray {
        val buffer = codec.getOutputBuffer(outputIndex) ?: return ShortArray(0)
        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)
        return if (isFloatOutput) {
            val floats = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            ShortArray(floats.remaining()) { index ->
                (floats.get(index) * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            }
        } else {
            val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            ShortArray(shorts.remaining()).also { shorts.get(it) }
        }
    }

    private const val TIMEOUT_US = 10_000L
}
