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
    fun `the encoder path is the one the installer pushes to, under noBackupFilesDir`() {
        val model = model()

        // Start from a state where the directory is gone, so the check below is about encoderFile()
        // rather than about an earlier test's leftovers.
        model.encoderFile().parentFile?.deleteRecursively()
        assertEquals(File(File(context.noBackupFilesDir, "parakeet-model"), ParakeetModel.ENCODER_NAME), model.encoderFile())
        // Building the path must not create anything: it is called to answer "is a download needed?"
        // long before a pass is allowed to write.
        assertFalse(model.encoderFile().exists())
        assertFalse(model.encoderFile().parentFile?.exists() ?: true)
    }

    @Test
    fun `every file of a pass sits in the same directory`() {
        val model = model()

        val directory = model.encoderFile().parentFile
        assertEquals(directory, model.melFile().parentFile)
        assertEquals(directory, model.decoderFile().parentFile)
        assertEquals(directory, model.tokenizerFile().parentFile)
        assertEquals("parakeet_redux_mel_10s.tflite", model.melFile().name)
        assertEquals("parakeet_redux_decoder_i8.tflite", model.decoderFile().name)
        assertEquals("tokenizer.json", model.tokenizerFile().name)
    }

    @Test
    fun `the CPU runs the 4-bit encoder and the accelerators the float16 one`() {
        val model = model()

        assertEquals("parakeet_redux_encoder_10s_i4.tflite", model.encoderFile(ParakeetBackend.CPU).name)
        assertEquals("parakeet_redux_encoder_10s_f16.tflite", model.encoderFile(ParakeetBackend.GPU).name)
        assertEquals("parakeet_redux_encoder_10s_f16.tflite", model.encoderFile(ParakeetBackend.NPU).name)
        assertNotEquals(model.encoderFile(ParakeetBackend.CPU), model.encoderFile(ParakeetBackend.GPU))
    }

    @Test
    fun `the part file sits beside the file it belongs to, under that file's own name`() {
        val model = model()

        val encoderPart = model.partFile(ParakeetModel.ENCODER_I4)
        assertEquals(File(model.directory(), "${ParakeetModel.ENCODER_NAME}.part"), encoderPart)
        assertEquals(model.encoderFile().parentFile, encoderPart.parentFile)

        val f16Part = model.partFile(ParakeetModel.ENCODER_F16)
        assertEquals(File(model.directory(), "${ParakeetModel.ENCODER_F16.name}.part"), f16Part)
        assertEquals(model.encoderFile(ParakeetBackend.GPU).parentFile, f16Part.parentFile)

        val tokenizerPart = model.partFile(ParakeetModel.TOKENIZER)
        assertEquals(File(model.directory(), "tokenizer.json.part"), tokenizerPart)
        assertEquals(model.tokenizerFile().parentFile, tokenizerPart.parentFile)
    }

    @Test
    fun `a missing model is not present`() {
        assertFalse(model().isPresent())
        assertFalse(model().isPresent(ParakeetBackend.GPU))
    }

    @Test
    fun `an encoder without its mel graph, decoder or tokenizer is not present`() {
        val model = model()
        model.directory()
        writeSized(model.encoderFile(), ParakeetModel.ENCODER_BYTES)

        assertFalse(model.isPresent(ParakeetBackend.CPU))
    }

    @Test
    fun `the CPU files alone are enough for the CPU but not for an accelerator`() {
        val model = model()
        model.directory()
        writeCpuFiles(model)

        assertTrue(model.isPresent(ParakeetBackend.CPU))
        assertFalse(
            "a CPU install has no float16 encoder, so an accelerator pass would have to fetch one",
            model.isPresent(ParakeetBackend.GPU),
        )
    }

    @Test
    fun `the float16 encoder adds every backend`() {
        val model = model()
        model.directory()
        writeCpuFiles(model)
        writeSized(model.encoderFile(ParakeetBackend.GPU), ParakeetModel.ENCODER_F16.bytes)

        ParakeetBackend.entries.forEach { backend -> assertTrue(backend.label, model.isPresent(backend)) }
    }

    @Test
    fun `installed files never reach the network`() = runTest {
        val model = model()
        model.directory()
        writeCpuFiles(model)

        // The client would fail the test through its interceptor if any request were attempted, so a
        // Present here pins the presence check that keeps a private build off the network entirely.
        assertEquals(ModelDownload.Present, model.ensureDownloaded(ParakeetBackend.CPU))
    }

    @Test
    fun `the model identity is stable because changing it re-downloads hundreds of megabytes`() {
        assertEquals("parakeet_redux_encoder_10s_i4.tflite", ParakeetModel.ENCODER_NAME)
        assertEquals(345_895_584L, ParakeetModel.ENCODER_BYTES)
        assertEquals("parakeet", ParakeetModel.ENGINE_TAG)
        assertTrue(ParakeetModel.ENCODER_URL.endsWith("/${ParakeetModel.ENCODER_NAME}"))
    }

    @Test
    fun `the default pass runs on the 4-bit encoder and asks for nothing else`() {
        assertEquals(ParakeetBackend.CPU, ParakeetBackend.preferred())
        assertEquals(
            listOf(
                "tokenizer.json",
                "parakeet_redux_mel_10s.tflite",
                "parakeet_redux_decoder_i8.tflite",
                "parakeet_redux_encoder_10s_i4.tflite",
            ),
            ParakeetModel.assets(ParakeetBackend.preferred()).map { it.name },
        )
        // The float16 encoder is plumbing for a later experiment, not part of an install: only a pass
        // pinned to an accelerator asks for it.
        assertEquals(
            "parakeet_redux_encoder_10s_f16.tflite",
            ParakeetModel.assets(ParakeetBackend.GPU).last().name,
        )
    }

    @Test
    fun `the version names the engine, the model, the runtime and the cue rules`() {
        assertEquals("parakeet-tdt-0.6b-v3-redux/i4/tflite-2.3.2/cues1", ParakeetModel.version)
        // An analysis is recognised as on-device by this prefix, so the engine tag has to stay at the
        // front of the stamped string.
        assertTrue(ParakeetModel.version.startsWith(ParakeetModel.ENGINE_TAG))
    }

    /** Every file a CPU pass needs, set to its exact length without writing the bytes. */
    private fun writeCpuFiles(model: ParakeetModel) {
        writeSized(model.melFile(), ParakeetModel.MEL.bytes)
        writeSized(model.encoderFile(ParakeetBackend.CPU), ParakeetModel.ENCODER_I4.bytes)
        writeSized(model.decoderFile(), ParakeetModel.DECODER.bytes)
        writeSized(model.tokenizerFile(), ParakeetModel.TOKENIZER.bytes)
    }

    private fun writeSized(file: File, bytes: Long) {
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
    }

    private fun model() = ParakeetModel(context, Lazy { client })

    /**
     * Any request fails the test instead of leaving the device, so a presence check that missed would
     * say so immediately rather than streaming hundreds of megabytes from Hugging Face.
     */
    private val client = OkHttpClient.Builder()
        .addInterceptor(
            object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response = throw AssertionError("a unit test must not reach the network")
            },
        )
        .build()
}
