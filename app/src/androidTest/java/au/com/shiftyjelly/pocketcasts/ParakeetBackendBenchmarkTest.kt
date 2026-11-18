package au.com.shiftyjelly.pocketcasts

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptRequest
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptResult
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptSource
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.ParakeetEpisodeTranscriber
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.ParakeetModel
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.ParakeetStats
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert.ParakeetBackend
import com.google.ai.edge.litert.NpuCompatibilityChecker
import dagger.Lazy
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real pipeline on the phone once per installed accelerator and reports how fast each one is.
 *
 * This is the measurement the LiteRT port exists for. The backends run the same mel graph and the
 * same CPU decoder; only the encoder's accelerator differs, so the `rtf` each pass logs is a
 * like-for-like comparison. A backend whose encoder is not installed is skipped, which is why the GPU
 * and NPU only appear after the float16 encoder has been pushed with
 * `install-model.ps1 -WithF16`.
 *
 * Two numbers come out of every pass:
 *
 * - `rtf` — audio time over wall time for the whole pass, including the CPU mel graph and the cue
 *   bookkeeping.
 * - `model_rtf` — audio time over the time the graphs themselves took (`encode_ms + decode_ms`),
 *   which is what says whether an accelerator is actually earning its place.
 *
 * The files are installed by `dev/adaware/next/parakeet/install-app.ps1`; the audio clip is copied in
 * by hand, because a usable minute of podcast is too big to keep in the repository:
 *
 * ```
 * adb push clip.mp3 /sdcard/Android/data/au.com.shiftyjelly.pocketcasts.debug/files/parakeet-fixture.mp3
 * ./gradlew :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w \
 *     -e class au.com.shiftyjelly.pocketcasts.ParakeetBackendBenchmarkTest \
 *     au.com.shiftyjelly.pocketcasts.testapp/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * A clip that contains one ad break is the useful one: the cue times around it show whether the
 * words land on the audio. A short clip understates every backend because opening the graph is paid
 * once per pass, so treat the ordering as the result and the absolute number as the floor.
 */
@RunWith(AndroidJUnit4::class)
class ParakeetBackendBenchmarkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun comparesEveryAcceleratorOnTheSameClip() {
        val clip = File(context.getExternalFilesDir(null), FIXTURE_NAME)
        assumeTrue("Push a $FIXTURE_NAME clip onto the device first, see the class comment", clip.isFile)

        val model = ParakeetModel(context, Lazy { OkHttpClient() })
        assumeTrue(
            "Push the graphs and tokenizer with install-model.ps1; ${model.directory().absolutePath} is incomplete",
            model.isPresent(ParakeetBackend.CPU),
        )
        val clipMs = durationOf(clip)
        assumeTrue("Could not read the duration of ${clip.absolutePath}", clipMs > 0L)

        Log.i(TAG, "Google Tensor NPU supported: ${NpuCompatibilityChecker.GoogleTensor.isDeviceSupported()}")
        Log.i(TAG, "SoC ${android.os.Build.SOC_MODEL}, ${clipMs}ms of audio, ${model.encoderFile().length()} byte encoder")

        // Only the backends whose encoder is on the device: the float16 export is not part of a
        // normal install (a CPU pass is the default), so pushing it is what adds the GPU and NPU rows.
        val backends = ParakeetBackend.entries.filter { model.isPresent(it) }
        Log.i(TAG, "Measuring ${backends.map { it.label }}; skipped ${(ParakeetBackend.entries - backends.toSet()).map { it.label }}")

        val results = mutableMapOf<ParakeetBackend, TranscriptResult>()
        for (backend in backends) {
            val transcriber = ParakeetEpisodeTranscriber(context = context, model = model).apply {
                this.backend = backend
            }
            val startedAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                runBlocking {
                    transcriber.transcribe(
                        TranscriptRequest(
                            episodeUuid = "parakeet-$backend-benchmark",
                            source = TranscriptSource.Downloaded(clip),
                            durationMs = clipMs,
                            podcastLanguage = "en",
                        ),
                    )
                }
            }.getOrElse { error ->
                Log.w(TAG, "$backend could not run: ${error.message}", error)
                continue
            }
            val wallMs = SystemClock.elapsedRealtime() - startedAt
            results[backend] = result
            Log.i(
                TAG,
                "BENCH backend=${backend.label} audio_ms=$clipMs wall_ms=$wallMs " +
                    "rtf=${ParakeetStats.formatRtf(ParakeetStats.rtf(clipMs, wallMs))} " +
                    narrate(result),
            )
        }

        assumeTrue("No accelerator ran the clip", results.isNotEmpty())
        results.forEach { (backend, result) ->
            val success = result as? TranscriptResult.Success
            assertTrue(
                "$backend returned $result instead of cues",
                success != null && success.cues.isNotEmpty(),
            )
            Log.i(TAG, "$backend text: ${success!!.cues.joinToString(" | ") { it.text }.take(400)}")
        }
    }

    /** The parts of a result worth putting on the summary line. */
    private fun narrate(result: TranscriptResult): String = when (result) {
        is TranscriptResult.Success -> "cues=${result.cues.size} chars=${result.cues.sumOf { it.text.length }}"
        is TranscriptResult.Failed -> "failed=\"${result.message}\""
        is TranscriptResult.Unavailable -> "unavailable=\"${result.reason}\""
    }

    /** The clip's length in milliseconds, or 0 when the container does not say. */
    private fun durationOf(clip: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(clip.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read ${clip.name}", e)
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val TAG = "ParakeetStt"
        const val FIXTURE_NAME = "parakeet-fixture.mp3"
    }
}
