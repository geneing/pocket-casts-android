package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet

import android.content.Context
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.ModelDownload
import dagger.Lazy
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ParakeetModelTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `the bundle path is the one the installer pushes to, under noBackupFilesDir`() {
        val model = model()

        // Start from a state where the directory is gone, so the check below is about file() rather
        // than about an earlier test's leftovers.
        model.file().parentFile?.deleteRecursively()
        assertEquals(File(File(context.noBackupFilesDir, "parakeet-model"), ParakeetModel.FILE_NAME), model.file())
        // Building the path must not create anything: it is called to answer "is a download needed?"
        // long before a pass is allowed to write.
        assertFalse(model.file().exists())
        assertFalse(model.file().parentFile?.exists() ?: true)
    }

    @Test
    fun `the model and cache directories are separate and created on demand`() {
        val model = model()

        val models = model.directory()
        assertEquals(File(context.noBackupFilesDir, "parakeet-model"), models)
        assertTrue(models.isDirectory)

        val cache = model.cacheDirectory()
        assertEquals(File(context.noBackupFilesDir, "parakeet-cache"), cache)
        assertTrue(cache.isDirectory)
        // MediaPipe's compiled artifact is disposable; keeping it out of the model directory is what
        // lets it be cleared without looking like a bundle that has to be downloaded again.
        assertNotEquals(models, cache)
    }

    @Test
    fun `the part file sits beside the bundle under the bundle's own name`() {
        val model = model()

        assertEquals(File(model.directory(), "${ParakeetModel.FILE_NAME}.part"), model.partFile())
        assertEquals(model.file().parentFile, model.partFile().parentFile)
    }

    @Test
    fun `a missing bundle is not present`() {
        assertFalse(model().isPresent())
    }

    @Test
    fun `a truncated bundle is not present, so the next pass downloads it again`() {
        val model = model()
        model.directory()
        model.file().writeBytes(ByteArray(64 * 1024))

        assertFalse(model.isPresent())
    }

    @Test
    fun `a bundle of exactly the expected size is present`() {
        val model = model()
        model.directory()
        // The rule reads the length, so the length is what this sets. The file is extended without
        // being filled, because writing 586 MiB of zeros to prove a size check is not worth the disk.
        RandomAccessFile(model.file(), "rw").use { it.setLength(ParakeetModel.EXPECTED_BYTES) }

        assertTrue(model.isPresent())
        model.file().delete()
    }

    @Test
    fun `an installed bundle never reaches the network`() = runTest {
        val model = model()
        model.directory()
        RandomAccessFile(model.file(), "rw").use { it.setLength(ParakeetModel.EXPECTED_BYTES) }

        // The client would fail the test through its interceptor if any request were attempted, so a
        // Present here pins the presence check that keeps a private build off the network entirely.
        assertEquals(ModelDownload.Present, model.ensureDownloaded())
        model.file().delete()
    }

    @Test
    fun `the model identity is stable because changing it re-downloads 586 MiB`() {
        assertEquals("parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm", ParakeetModel.FILE_NAME)
        assertEquals(614_588_752L, ParakeetModel.EXPECTED_BYTES)
        assertEquals("parakeet", ParakeetModel.ENGINE_TAG)
        assertTrue(ParakeetModel.URL.endsWith("/${ParakeetModel.FILE_NAME}"))
    }

    @Test
    fun `the version names the engine, the model, the runtime and the cue rules`() {
        assertEquals("parakeet-tdt-0.6b-v3/i8-5s-stateful/litertlm-1.2.3/cues1", ParakeetModel.version)
        // An analysis is recognised as on-device by this prefix, so the engine tag has to stay at the
        // front of the stamped string.
        assertTrue(ParakeetModel.version.startsWith(ParakeetModel.ENGINE_TAG))
    }

    private fun model() = ParakeetModel(context, Lazy { client })

    /**
     * Any request fails the test instead of leaving the device, so a presence check that missed would
     * say so immediately rather than streaming 586 MiB from Hugging Face.
     */
    private val client = OkHttpClient.Builder()
        .addInterceptor(
            object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response = throw AssertionError("a unit test must not reach the network")
            },
        )
        .build()
}
