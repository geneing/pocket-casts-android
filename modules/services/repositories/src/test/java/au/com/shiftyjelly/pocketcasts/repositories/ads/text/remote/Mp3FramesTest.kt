package au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The MP3 splitter is the one part of the remote pass that can be checked without a device: it is
 * byte arithmetic over frame headers, and the timestamps it reports are what every cue of a chunk is
 * placed by. A frame's position is computed from its index rather than summed from frame lengths,
 * which is what keeps the tenth chunk of a two hour episode in the right place.
 */
class Mp3FramesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun cutsAChunkOnFrameBoundariesAndReportsTheirRealTime() {
        val file = write("plain.mp3", frames(FRAMES))

        val slice = slice(file, fromMs = 0L, untilMs = 5_000L, maxBytes = Long.MAX_VALUE)

        assertNotNull(slice)
        // 5 000 ms of 26.122 ms frames is 191 frames, and the chunk ends on the frame that passes
        // the request rather than inside one.
        assertEquals(192 * FRAME_BYTES, slice!!.file.length().toInt())
        assertEquals(0L, slice.originMs)
        assertEquals(5_015L, slice.audioMs)
        assertEquals("mp3", slice.format)
        assertTrue(slice.copied)
    }

    @Test
    fun aCutStartsOnTheFrameThatContainsTheRequestedPosition() {
        val file = write("plain.mp3", frames(3_000))

        val slice = slice(file, fromMs = 60_000L, untilMs = 70_000L, maxBytes = Long.MAX_VALUE)

        assertNotNull(slice)
        // The chunk starts one frame early at most, never one late: a late start would drop speech
        // and put every cue of the chunk after it in the wrong place.
        assertTrue("origin ${slice!!.originMs} is later than the request", slice.originMs <= 60_000L)
        assertTrue("origin ${slice.originMs} is more than a frame early", slice.originMs > 60_000L - FRAME_MS - 1L)
        // The copy is a valid MP3 by itself: it starts with a frame sync, not with the middle of one.
        val head = slice.file.inputStream().use { input -> ByteArray(2).also { input.read(it) } }
        assertEquals(0xFF, head[0].toInt() and 0xFF)
        assertEquals(0xE0, head[1].toInt() and 0xE0)
    }

    @Test
    fun stopsAtTheByteLimitSoOneRequestStaysUnderTheBodyLimit() {
        val file = write("plain.mp3", frames(FRAMES))

        val slice = slice(file, fromMs = 0L, untilMs = 10 * 60_000L, maxBytes = 10L * FRAME_BYTES)

        assertNotNull(slice)
        assertEquals(10 * FRAME_BYTES, slice!!.file.length().toInt())
    }

    @Test
    fun skipsAnId3TagInFrontOfTheFirstFrame() {
        val file = write("tagged.mp3", id3Tag(20) + frames(FRAMES))

        val slice = slice(file, fromMs = 0L, untilMs = 1_000L, maxBytes = Long.MAX_VALUE)

        assertNotNull(slice)
        assertEquals(0L, slice!!.originMs)
        assertEquals(39 * FRAME_BYTES, slice.file.length().toInt())
    }

    @Test
    fun toleratesAnId3v1TagAfterTheLastFrame() {
        val file = write("id3v1.mp3", frames(FRAMES) + id3v1Tag())

        val slice = slice(file, fromMs = 0L, untilMs = 1_000L, maxBytes = Long.MAX_VALUE)

        assertNotNull(slice)
        assertEquals(39 * FRAME_BYTES, slice!!.file.length().toInt())
    }

    @Test
    fun theLastChunkEndsAtTheLastFrame() {
        val file = write("plain.mp3", frames(FRAMES))

        val slice = slice(file, fromMs = 9_000L, untilMs = 60 * 60_000L, maxBytes = Long.MAX_VALUE)

        assertNotNull(slice)
        // 9 000 ms lands in frame 344, and the chunk runs to the end of the file's last frame.
        assertEquals((FRAMES - 344) * FRAME_BYTES, slice!!.file.length().toInt())
    }

    @Test
    fun refusesAFileThatIsNotAFrameStream() {
        val file = write("noise.mp3", ByteArray(64 * 1024) { (it * 31).toByte() })

        assertNull(Mp3Frames.open(file))
    }

    @Test
    fun refusesAFileWhoseFramesBreakPartWayThrough() {
        val file = write("damaged.mp3", frames(100) + ByteArray(4 * 1024) { 0x00 })

        assertNull(Mp3Frames.open(file))
    }

    private fun slice(file: File, fromMs: Long, untilMs: Long, maxBytes: Long): ChunkAudio? {
        val splitter = Mp3Frames.open(file) ?: return null
        return splitter.slice(fromMs, untilMs, maxBytes, folder.newFile())
    }

    private fun write(name: String, bytes: ByteArray): File = folder.newFile(name).apply { writeBytes(bytes) }

    /** N frames of 128 kbit/s, 44.1 kHz, MPEG 1 layer III: 417 bytes and 26.122 ms each. */
    private fun frames(count: Int): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(count) {
            out.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00))
            out.write(ByteArray(FRAME_BYTES - HEADER_BYTES))
        }
        return out.toByteArray()
    }

    /** An ID3v2.4 tag of [bytes] of padding: what a tagger writes in front of the first frame. */
    private fun id3Tag(bytes: Int): ByteArray {
        val size = byteArrayOf(
            ((bytes shr 21) and 0x7F).toByte(),
            ((bytes shr 14) and 0x7F).toByte(),
            ((bytes shr 7) and 0x7F).toByte(),
            (bytes and 0x7F).toByte(),
        )
        return "ID3".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x04, 0x00, 0x00) +
            size +
            ByteArray(bytes)
    }

    /** The 128 byte tag some encoders append after the last frame. */
    private fun id3v1Tag(): ByteArray = "TAG".toByteArray(Charsets.US_ASCII) + ByteArray(125)

    private companion object {
        const val FRAMES = 3_000
        const val FRAME_BYTES = 417
        const val HEADER_BYTES = 4

        /** One frame at 44.1 kHz: 1 152 samples, 26.122 ms. */
        const val FRAME_MS = 26L
    }
}
