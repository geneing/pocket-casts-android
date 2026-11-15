package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import java.io.File
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAudioDownloadTest {
    private val client = OkHttpClient()

    @Test
    fun `a streamed episode is written to the target file`() {
        MockWebServer().use { server ->
            val audio = ByteArray(64 * 1024) { (it % 251).toByte() }
            server.enqueue(MockResponse().setBody(okio.Buffer().write(audio)))
            server.start()
            val target = File.createTempFile("stream-audio", ".audio").apply { delete() }

            val file = downloadAudioStream(client, server.url("/episode.mp3").toString(), target)

            assertEquals(target, file)
            assertArrayEquals(audio, target.readBytes())
            target.delete()
        }
    }

    @Test
    fun `an HTTP error leaves no partial file behind`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            val target = File.createTempFile("stream-audio", ".audio").apply { delete() }

            val file = downloadAudioStream(client, server.url("/missing.mp3").toString(), target)

            assertNull(file)
            assertEquals(false, target.exists())
        }
    }
}
