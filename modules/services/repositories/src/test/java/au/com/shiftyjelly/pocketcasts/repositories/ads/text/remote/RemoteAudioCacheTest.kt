package au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RemoteAudioCacheTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var cache: RemoteAudioCache

    @Before
    fun setUp() {
        cache = RemoteAudioCache(context)
        cache.directory().listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `a pass removes only its own episode's files`() {
        val mine = cache.file(EPISODE, "m4a").apply { writeText("chunk") }
        val stream = cache.file(EPISODE, "mp3").apply { writeText("copy") }
        val other = cache.file(OTHER_EPISODE, "m4a").apply { writeText("theirs") }

        cache.deleteFor(EPISODE)

        assertFalse(mine.exists())
        assertFalse(stream.exists())
        assertTrue("another episode's file must survive", other.exists())
    }

    @Test
    fun `a sweep removes only files old enough that no pass can be reading them`() {
        val now = System.currentTimeMillis()
        val stale = cache.file(EPISODE, "mp3").apply {
            writeText("x".repeat(1_000))
            setLastModified(now - RemoteAudioCache.STALE_MS - 1_000)
        }
        val fresh = cache.file(OTHER_EPISODE, "m4a").apply { writeText("y".repeat(10)) }

        val (files, bytes) = cache.deleteStale(now = now)

        assertEquals(1, files)
        assertEquals(1_000L, bytes)
        assertFalse(stale.exists())
        assertTrue("a file a live pass may be reading is kept", fresh.exists())
    }

    @Test
    fun `a sweep of an empty directory reports nothing`() {
        assertEquals(0 to 0L, cache.deleteStale())

        cache.file(EPISODE, "m4a").writeText("z")

        assertEquals(0 to 0L, cache.deleteStale())
    }

    @Test
    fun `files are written into the app cache, never into files`() {
        val file = cache.file(EPISODE, "m4a")

        assertEquals(File(context.cacheDir, RemoteAudioCache.DIRECTORY), file.parentFile)
        assertFalse("temporary audio must not be stored as app data", file.absolutePath.startsWith(context.filesDir.absolutePath))
    }

    private companion object {
        const val EPISODE = "remote-audio-cache-test"
        const val OTHER_EPISODE = "remote-audio-cache-test-other"
    }
}
