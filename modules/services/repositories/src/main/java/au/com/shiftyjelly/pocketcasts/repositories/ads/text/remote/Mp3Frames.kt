package au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote

import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import kotlin.math.min

/**
 * Splits an MPEG-1/2 audio layer III file into chunks by copying whole frames.
 *
 * An MP3 has no container and no index: it is a chain of frames, each introduced by a four byte
 * header that gives the frame's length and sample rate, and any run of whole frames is a valid MP3
 * file by itself. So one pass over the headers indexes the file, a chunk is one byte copy between
 * two frame boundaries, and nothing is decoded or re-encoded — the most expensive resource in the
 * pass stops being the CPU and becomes the network.
 *
 * Cutting between frames is the only place the file can be cut, so a chunk does not begin exactly
 * where it was asked to. Each frame is 26 ms of audio at 44.1 kHz, and every chunk reports the frame
 * it really starts on as [ChunkAudio.originMs] rather than the position that was requested; adding
 * that origin to the model's own timestamps is what keeps a cue on the audio after a seam.
 */
internal class Mp3Frames private constructor(
    private val file: File,
    private val sampleRate: Int,
    private val samplesPerFrame: Int,
    private val starts: LongArray,
    private val lengths: IntArray,
) : AudioSplitter {
    override val format = FORMAT

    override val extension = "mp3"

    override fun slice(fromMs: Long, untilMs: Long, maxBytes: Long, target: File): ChunkAudio? {
        val first = frameAt(fromMs)
        if (first >= starts.size) return null
        // The frame that contains the start is included whole, so the copy is never late; the end
        // is the first frame at or past untilMs. One frame is always copied, even past maxBytes,
        // because an empty chunk is not a chunk.
        var last = first + 1
        var bytes = lengths[first].toLong()
        while (last < starts.size) {
            if (timeMs(last) >= untilMs) break
            val next = lengths[last].toLong()
            if (bytes + next > maxBytes) break
            bytes += next
            last++
        }
        val fromByte = starts[first]
        val untilByte = starts[last - 1] + lengths[last - 1]
        if (!copyRange(fromByte, untilByte, target)) return null
        val originMs = timeMs(first)
        return ChunkAudio(
            file = target,
            originMs = originMs,
            audioMs = timeMs(last) - originMs,
            format = format,
            copied = true,
        )
    }

    /** The frame that contains [ms]: a chunk may start a frame early, never a frame late. */
    private fun frameAt(ms: Long): Int = (ms.coerceAtLeast(0L) * sampleRate / (samplesPerFrame * MILLIS_PER_SECOND))
        .coerceAtMost(starts.size.toLong())
        .toInt()

    /** The position of [frame] from the first frame, computed from its index so it cannot drift. */
    private fun timeMs(frame: Int): Long = frame.toLong() * samplesPerFrame * MILLIS_PER_SECOND / sampleRate

    /** Copies the bytes between two frame boundaries into [target]. */
    private fun copyRange(fromByte: Long, untilByte: Long, target: File): Boolean = try {
        target.parentFile?.mkdirs()
        FileInputStream(file).channel.use { source ->
            FileOutputStream(target).channel.use { sink ->
                var position = fromByte
                while (position < untilByte) {
                    val copied = source.transferTo(position, min(TRANSFER_BYTES, untilByte - position), sink)
                    if (copied <= 0L) break
                    position += copied
                }
            }
        }
        target.length() == untilByte - fromByte
    } catch (e: IOException) {
        LogBuffer.w(LOG_TAG, "Could not copy ${file.name} bytes $fromByte..$untilByte: ${e.message}")
        false
    }

    companion object {
        /** The `input_audio.format` an MP3 chunk is sent as. */
        const val FORMAT = "mp3"

        private const val LOG_TAG = "RemoteAsr"
        private const val HEADER_BYTES = 4
        private const val ID3_HEADER_BYTES = 10
        private const val ID3V1_BYTES = 128
        private const val MIN_FILE_BYTES = 4 * 1024L
        private const val MIN_FRAMES = 2
        private const val INITIAL_FRAMES = 1024
        private const val MILLIS_PER_SECOND = 1_000L
        private const val TRANSFER_BYTES = 1L shl 20

        private val MPEG1_SAMPLE_RATES = intArrayOf(44_100, 48_000, 32_000)
        private val MPEG2_SAMPLE_RATES = intArrayOf(22_050, 24_000, 16_000)
        private val MPEG25_SAMPLE_RATES = intArrayOf(11_025, 12_000, 8_000)

        private val MPEG1_LAYER3_BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        private val MPEG2_LAYER3_BITRATES = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

        /** The file's frame index, or null when it is not a plain layer III frame stream. */
        fun open(file: File): AudioSplitter? = try {
            index(file)
        } catch (e: IOException) {
            LogBuffer.w(LOG_TAG, "Could not read the MP3 frames of ${file.name}: ${e.message}")
            null
        }

        private fun index(file: File): AudioSplitter? {
            val total = file.length()
            if (total < MIN_FILE_BYTES) return null
            PushbackInputStream(
                BufferedInputStream(FileInputStream(file), 64 * 1024),
                ID3_HEADER_BYTES,
            ).use { input ->
                val head = ByteArray(ID3_HEADER_BYTES)
                if (!input.readFully(head, head.size)) return null
                var position = 0L
                if (isId3(head)) {
                    // A tag in front of the first frame is skipped, not uploaded: it costs bytes and
                    // carries nothing the recogniser reads. Its size is a 28-bit syncsafe integer.
                    val tagBytes = id3TagBytes(head)
                    if (!input.skipFully(tagBytes)) return null
                    position = ID3_HEADER_BYTES + tagBytes
                } else {
                    input.unread(head)
                }
                return frames(file, input, position, total)
            }
        }

        private fun frames(file: File, input: InputStream, start: Long, total: Long): AudioSplitter? {
            var starts = LongArray(INITIAL_FRAMES)
            var lengths = IntArray(INITIAL_FRAMES)
            var count = 0
            var position = start
            var sampleRate = 0
            var samplesPerFrame = 0
            val header = ByteArray(HEADER_BYTES)
            while (position + HEADER_BYTES <= total) {
                if (!input.readFully(header, HEADER_BYTES)) break
                val frame = parse(header) ?: run {
                    // Only a tag after the last frame is allowed to end the chain; anything else
                    // means this is not a plain frame stream and the file has to be re-encoded.
                    if (total - position <= ID3V1_BYTES) break else return null
                }
                if (position + frame.length > total) break
                if (sampleRate == 0) {
                    sampleRate = frame.sampleRate
                    samplesPerFrame = frame.samplesPerFrame
                } else if (frame.sampleRate != sampleRate) {
                    // A sample rate change is not a legal layer III stream; a decoder would lose
                    // the timeline, so would the cues.
                    return null
                }
                if (count == starts.size) {
                    starts = starts.copyOf(count * 2)
                    lengths = lengths.copyOf(count * 2)
                }
                starts[count] = position
                lengths[count] = frame.length
                count++
                if (!input.skipFully((frame.length - HEADER_BYTES).toLong())) break
                position += frame.length
            }
            if (count < MIN_FRAMES) return null
            return Mp3Frames(file, sampleRate, samplesPerFrame, starts.copyOf(count), lengths.copyOf(count))
        }

        /**
         * The header of one frame, or null when these four bytes do not begin a layer III frame.
         * Only layer III is accepted: it is what podcasts are published in, and the frame length
         * arithmetic differs enough per layer that guessing would silently misplace every cue.
         */
        private fun parse(header: ByteArray): Mp3Frame? {
            val b1 = header[1].toInt() and 0xFF
            val b2 = header[2].toInt() and 0xFF
            if ((header[0].toInt() and 0xFF) != 0xFF || (b1 and 0xE0) != 0xE0) return null
            // 3 = MPEG 1, 2 = MPEG 2, 0 = MPEG 2.5, 1 = reserved.
            val version = (b1 shr 3) and 0x03
            if (((b1 shr 1) and 0x03) != LAYER_III) return null
            val bitrateIndex = (b2 shr 4) and 0x0F
            val sampleRateIndex = (b2 shr 2) and 0x03
            if (bitrateIndex == 0 || bitrateIndex == 0x0F || sampleRateIndex == 0x03) return null
            val padding = (b2 shr 1) and 0x01
            val sampleRates = when (version) {
                3 -> MPEG1_SAMPLE_RATES
                2 -> MPEG2_SAMPLE_RATES
                0 -> MPEG25_SAMPLE_RATES
                else -> return null
            }
            val bitrates = if (version == 3) MPEG1_LAYER3_BITRATES else MPEG2_LAYER3_BITRATES
            val kbps = bitrates[bitrateIndex]
            if (kbps == 0) return null
            // MPEG 1 layer III carries 1 152 samples in 144 bytes per kbit/s; MPEG 2 and 2.5 carry
            // half the samples in half the bytes, which is why the divisor changes with the version.
            val mpeg1 = version == 3
            val sampleRate = sampleRates[sampleRateIndex]
            val length = (if (mpeg1) 144 else 72) * kbps * 1_000 / sampleRate + padding
            if (length <= HEADER_BYTES) return null
            return Mp3Frame(length, sampleRate, if (mpeg1) 1_152 else 576)
        }

        private fun isId3(head: ByteArray): Boolean = String(head, 0, 3, Charsets.US_ASCII) == "ID3"

        /** The bytes between the ten byte ID3v2 header and the first frame. */
        private fun id3TagBytes(head: ByteArray): Long {
            val size = ((head[6].toInt() and 0x7F) shl 21) or
                ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or
                (head[9].toInt() and 0x7F)
            val footer = if ((head[5].toInt() and 0x10) != 0) 10 else 0
            return size.toLong() + footer
        }

        private const val LAYER_III = 1

        private fun InputStream.readFully(buffer: ByteArray, count: Int): Boolean {
            var read = 0
            while (read < count) {
                val bytes = read(buffer, read, count - read)
                if (bytes < 0) return false
                read += bytes
            }
            return true
        }

        private fun InputStream.skipFully(count: Long): Boolean {
            var left = count
            while (left > 0L) {
                val skipped = skip(left)
                if (skipped > 0L) {
                    left -= skipped
                } else if (read() < 0) {
                    return false
                } else {
                    left--
                }
            }
            return true
        }
    }
}

private class Mp3Frame(val length: Int, val sampleRate: Int, val samplesPerFrame: Int)
