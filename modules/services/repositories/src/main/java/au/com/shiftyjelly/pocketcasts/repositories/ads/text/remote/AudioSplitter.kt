package au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A chunk of episode audio that is ready to upload, and where in the episode it begins.
 *
 * [originMs] is the episode time of the chunk's first frame, so the model's clock — which starts at
 * zero for every request — maps onto the episode by adding it. It is the frame's real position, not
 * the one the chunk was asked for: a compressed file can only be cut between frames, so a chunk
 * asked for 600 000 ms can begin a frame earlier, and every cue of that chunk carries the
 * difference. Guessing the requested position instead would put each cue up to a frame late, once
 * per chunk, and the ad detector compares cue times with the waveform.
 */
internal data class ChunkAudio(
    val file: File,
    val originMs: Long,
    /** How much audio the chunk carries, from [originMs] to the end of its last frame. */
    val audioMs: Long,
    /** The `input_audio.format` the request is sent as. */
    val format: String,
    /** True when the bytes are a copy of the episode's own audio, false when they were encoded. */
    val copied: Boolean,
)

/**
 * Cuts the episode's compressed audio into uploads without decoding it.
 *
 * The endpoint takes one container per request and an episode is usually several, so a pass has to
 * cut the file somewhere. Cutting compressed audio is a byte copy — the frames are already in the
 * file and a run of whole frames is a valid file on its own — so a chunk costs one pass over the
 * frame headers and one copy of its bytes. That is a few hundred milliseconds and almost no power
 * for an episode, against the minutes of audio decoding and AAC encoding that a re-encode spends.
 *
 * Only a file the platform cannot repack as it is gets re-encoded, by [AacChunks]: a WAV, which is
 * not compressed at all, and a container whose codec the endpoint's container cannot carry.
 *
 * [slice] returns null when there is no audio at or after the requested position. It throws when
 * the file cannot be split at all, which the pass answers by re-encoding the rest of the episode.
 */
internal interface AudioSplitter {
    /** The `input_audio.format` a chunk of this file is sent as. */
    val format: String

    /** The extension a chunk file is written with. */
    val extension: String

    /**
     * Copies the episode audio in `[fromMs, untilMs)`, whole frames only, into [target].
     *
     * @param maxBytes the most audio one request may carry, before base64; a slice stops at the last
     *   frame that still fits, so the cut is never past the endpoint's body limit.
     */
    fun slice(fromMs: Long, untilMs: Long, maxBytes: Long, target: File): ChunkAudio?

    companion object {
        private const val LOG_TAG = "RemoteAsr"
        private const val MIN_FILE_BYTES = 4 * 1024L

        /** The splitter for this file, or null when it has to be decoded and re-encoded. */
        fun open(file: File): AudioSplitter? {
            if (file.length() < MIN_FILE_BYTES) return null
            return if (isMpegAudio(file)) Mp3Frames.open(file) else Mp4Remux.open(file)
        }

        /** True when the file starts like an MP3: an ID3v2 tag, or a frame sync. */
        private fun isMpegAudio(file: File): Boolean {
            val head = ByteArray(4)
            val read = runCatching {
                file.inputStream().use { it.read(head) }
            }.getOrDefault(0)
            if (read < 4) return false
            if (String(head, 0, 3, Charsets.US_ASCII) == "ID3") return true
            return (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0
        }
    }
}

/**
 * The codecs an MP4 can carry and the platform's muxer can write, as MediaExtractor names them. A
 * codec outside this set is re-encoded instead of being repacked: the muxer does not write Vorbis,
 * for example, and a request the endpoint cannot decode is a paid failure.
 */
private val MUXABLE_AUDIO = setOf(
    "audio/mp4a-latm",
    "audio/mpeg",
    "audio/opus",
    "audio/alac",
    "audio/flac",
    "audio/amr-nb",
    "audio/amr-wb",
)

/** The audio track of [extractor], or null when the file carries none. */
private fun audioTrack(extractor: MediaExtractor): Int? {
    for (track in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith("audio/")) return track
    }
    return null
}

/**
 * Cuts an MP4/M4A — or any container whose audio the platform can demux and the muxer can write —
 * by copying its samples into a new MP4.
 *
 * The samples are already compressed, so they are written as they are; the muxer only replaces the
 * index around them. Each chunk is a standalone MP4 that begins at the sample the cut landed on, and
 * that sample's own time, read from the file, is the chunk's origin.
 */
private class Mp4Remux(private val file: File) : AudioSplitter {
    override val format = FORMAT

    override val extension = "m4a"

    override fun slice(fromMs: Long, untilMs: Long, maxBytes: Long, target: File): ChunkAudio? {
        target.parentFile?.mkdirs()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = audioTrack(extractor) ?: throw IOException("${file.name} has no audio track")
            extractor.selectTrack(track)
            val sourceFormat = extractor.getTrackFormat(track)
            val fromUs = fromMs.coerceAtLeast(0L) * MILLIS_PER_SECOND
            val untilUs = if (untilMs == Long.MAX_VALUE) Long.MAX_VALUE else untilMs * MILLIS_PER_SECOND

            // Previous sync sample: an audio track's samples are all sync samples, so this lands on
            // the sample at or before the cut and the copy never loses audio at the seam.
            extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val sinkTrack = muxer.addTrack(sourceFormat)
            muxer.start()

            val sample = ByteBuffer.allocateDirect(inputBufferBytes(sourceFormat))
            val info = MediaCodec.BufferInfo()
            var firstUs = -1L
            var lastUs = -1L
            var endUs = -1L
            var bytes = 0L
            while (true) {
                sample.clear()
                val size = extractor.readSampleData(sample, 0)
                if (size < 0) break
                val timeUs = extractor.sampleTime
                if (timeUs < fromUs) {
                    extractor.advance()
                    continue
                }
                // The first sample past the window is the end of the audio, and it is read before
                // the break so the chunk's duration is measured rather than assumed.
                if (timeUs >= untilUs || (firstUs >= 0L && bytes + size > maxBytes)) {
                    endUs = timeUs
                    break
                }
                if (firstUs < 0L) firstUs = timeUs
                // Timestamps are rebased on the chunk's first sample: the muxer writes this file's
                // own clock, and the model's zero is the origin reported below.
                info.set(0, size, timeUs - firstUs, 0)
                muxer.writeSampleData(sinkTrack, sample, info)
                lastUs = timeUs
                bytes += size
                extractor.advance()
            }
            if (firstUs < 0L) {
                muxer.release()
                muxer = null
                return null
            }
            muxer.stop()
            muxer.release()
            muxer = null
            LogBuffer.i(
                LOG_TAG,
                "m4a: audio=${(lastUs - firstUs) / MILLIS_PER_SECOND}ms from=${fromMs}ms " +
                    "origin=${firstUs / MILLIS_PER_SECOND}ms bytes=$bytes",
            )
            return ChunkAudio(
                file = target,
                originMs = firstUs / MILLIS_PER_SECOND,
                audioMs = when {
                    endUs > firstUs -> endUs - firstUs
                    lastUs >= firstUs -> lastUs - firstUs + 1L
                    else -> 1L
                },
                format = format,
                copied = true,
            )
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    /** A read buffer big enough for the track's largest sample, with a floor for the usual ones. */
    private fun inputBufferBytes(format: MediaFormat): Int {
        val declared = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            0
        }
        return declared.coerceIn(MIN_SAMPLE_BYTES, MAX_SAMPLE_BYTES)
    }

    companion object {
        /** The `input_audio.format` an MP4 chunk is sent as. */
        const val FORMAT = "m4a"

        private const val LOG_TAG = "RemoteAsr"
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MIN_SAMPLE_BYTES = 64 * 1024
        private const val MAX_SAMPLE_BYTES = 1024 * 1024

        /** This file as a splitter, or null when it is not a container the muxer can rewrite. */
        fun open(file: File): AudioSplitter? {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val track = audioTrack(extractor) ?: return null
                val mime = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME) ?: return null
                if (mime !in MUXABLE_AUDIO) return null
                return Mp4Remux(file)
            } catch (e: IOException) {
                LogBuffer.w(LOG_TAG, "Could not read the tracks of ${file.name}: ${e.message}")
                return null
            } catch (e: RuntimeException) {
                LogBuffer.w(LOG_TAG, "Could not read the tracks of ${file.name}: ${e.message}")
                return null
            } finally {
                runCatching { extractor.release() }
            }
        }
    }
}
