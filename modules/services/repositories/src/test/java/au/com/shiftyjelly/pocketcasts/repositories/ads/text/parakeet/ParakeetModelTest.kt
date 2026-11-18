package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet

import android.content.Context
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.ModelDownload
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert.ParakeetBackend
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
    fun `the portable graph path is the one the installer pushes to, under noBackupFilesDir`() {
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
    fun `each backend names its own graph and they are not the same file`() {
        val model = model()

        assertEquals("parakeet_tdt_0.6b_v3_5s_f32_stateful_Google_Tensor_G6.tflite", model.file(ParakeetBackend.NPU).name)
        assertEquals("parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite", model.file(ParakeetBackend.CPU).name)
        // The GPU runs the portable export too: the NPU graph's compiled ops cannot execute anywhere
        // but the NPU, so it can never stand in for the CPU/GPU one.
        assertEquals(model.file(ParakeetBackend.CPU), model.file(ParakeetBackend.GPU))
        assertNotEquals(model.file(ParakeetBackend.CPU), model.file(ParakeetBackend.NPU))
        assertEquals(model.file(ParakeetBackend.CPU).parentFile, model.tokenizerFile().parentFile)
    }

    @Test
    fun `the part file sits beside the file it belongs to, under that file's own name`() {
        val model = model()

        val graphPart = model.partFile(ParakeetModel.GRAPH)
        assertEquals(File(model.directory(), "${ParakeetModel.FILE_NAME}.part"), graphPart)
        assertEquals(model.file().parentFile, graphPart.parentFile)

        val npuPart = model.partFile(ParakeetModel.NPU_GRAPH)
        assertEquals(File(model.directory(), "${ParakeetModel.NPU_GRAPH.name}.part"), npuPart)
        assertEquals(model.file(ParakeetBackend.NPU).parentFile, npuPart.parentFile)

        val tokenizerPart = model.partFile(ParakeetModel.TOKENIZER)
        assertEquals(File(model.directory(), "tokenizer.json.part"), tokenizerPart)
        assertEquals(model.tokenizerFile().parentFile, tokenizerPart.parentFile)
    }

    @Test
    fun `a missing graph is not present`() {
        assertFalse(model().isPresent())
        assertFalse(model().isPresent(ParakeetBackend.NPU))
    }

    @Test
    fun `a graph without its tokenizer is not present, so the next pass downloads both`() {
        val model = model()
        model.directory()
        writeSized(model.file(), ParakeetModel.EXPECTED_BYTES)

        assertFalse(model.isPresent(ParakeetBackend.CPU))
    }

    @Test
    fun `the portable graph alone is enough for the CPU but not for the NPU`() {
        val model = model()
        model.directory()
        writeSized(model.file(), ParakeetModel.EXPECTED_BYTES)
        writeSized(model.tokenizerFile(), ParakeetModel.TOKENIZER.bytes)

        assertTrue(model.isPresent(ParakeetBackend.CPU))
        assertFalse("a CPU pass has no NPU graph, so an NPU pass would have to fetch one", model.isPresent(ParakeetBackend.NPU))
    }

    @Test
    fun `all three files at exactly their expected sizes are present on every backend`() {
        val model = model()
        model.directory()
        // The rule reads the length, so the length is what this sets. The graphs are extended without
        // being filled, because writing 1.8 GiB of zeros to prove a size check is not worth the disk.
        writeSized(model.file(), ParakeetModel.EXPECTED_BYTES)
        writeSized(model.file(ParakeetBackend.NPU), ParakeetModel.NPU_GRAPH.bytes)
        writeSized(model.tokenizerFile(), ParakeetModel.TOKENIZER.bytes)

        ParakeetBackend.entries.forEach { backend -> assertTrue(backend.label, model.isPresent(backend)) }
    }

    @Test
    fun `installed files never reach the network`() = runTest {
        val model = model()
        model.directory()
        writeSized(model.file(), ParakeetModel.EXPECTED_BYTES)
        writeSized(model.tokenizerFile(), ParakeetModel.TOKENIZER.bytes)

        // The client would fail the test through its interceptor if any request were attempted, so a
        // Present here pins the presence check that keeps a private build off the network entirely.
        assertEquals(ModelDownload.Present, model.ensureDownloaded(ParakeetBackend.CPU))
    }

    @Test
    fun `the model identity is stable because changing it re-downloads gigabytes`() {
        assertEquals("parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite", ParakeetModel.FILE_NAME)
        assertEquals(614_261_072L, ParakeetModel.EXPECTED_BYTES)
        assertEquals("parakeet", ParakeetModel.ENGINE_TAG)
        assertTrue(ParakeetModel.URL.endsWith("/${ParakeetModel.FILE_NAME}"))
    }

    @Test
    fun `the default pass runs on the portable graph and asks for nothing else`() {
        assertEquals(ParakeetBackend.CPU, ParakeetBackend.preferred())
        assertEquals(
            listOf("parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite", "tokenizer.json"),
            ParakeetModel.assets(ParakeetBackend.preferred()).map { it.name },
        )
        // The NPU graph is plumbing for a later experiment, not part of an install: only a pass pinned
        // to the NPU asks for it.
        assertEquals(
            listOf("parakeet_tdt_0.6b_v3_5s_f32_stateful_Google_Tensor_G6.tflite", "tokenizer.json"),
            ParakeetModel.assets(ParakeetBackend.NPU).map { it.name },
        )
    }

    @Test
    fun `the version names the engine, the model, the runtime and the cue rules`() {
        assertEquals("parakeet-tdt-0.6b-v3/i8-stateful/tflite-2.3.2/cues1", ParakeetModel.version)
        // An analysis is recognised as on-device by this prefix, so the engine tag has to stay at the
        // front of the stamped string.
        assertTrue(ParakeetModel.version.startsWith(ParakeetModel.ENGINE_TAG))
    }

    private fun writeSized(file: File, bytes: Long) {
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
    }

    private fun model() = ParakeetModel(context, Lazy { client })

    /**
     * Any request fails the test instead of leaving the device, so a presence check that missed would
     * say so immediately rather than streaming gigabytes from Hugging Face.
     */
    private val client = OkHttpClient.Builder()
        .addInterceptor(
            object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response = throw AssertionError("a unit test must not reach the network")
            },
        )
        .build()
}
