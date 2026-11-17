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
import dagger.Lazy
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real speech engine on the device, over a clip the bundle and the decoder can both handle.
 *
 * This is the only check that covers what unit tests cannot: that the LiteRT-LM interpreter loads on
 * this ABI, that the pushed bundle is the one the engine expects, that MediaPipe accepts our 16 kHz
 * PCM feed, and how fast it all is. The bundle is installed by
 * `dev/adaware/next/parakeet/install-app.ps1` (or downloaded on Wi-Fi by the app itself); the audio
 * clip is copied in by hand, because a usable minute of podcast is too big to keep in the
 * repository:
 *
 * ```
 * adb push clip.mp3 /sdcard/Android/data/au.com.shiftyjelly.pocketcasts.debug/files/parakeet-fixture.mp3
 * ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=au.com.shiftyjelly.pocketcasts.ParakeetDeviceTranscriptionTest
 * ```
 *
 * A clip that contains one ad break is the useful one: the cue times around it show whether the
 * segment boundaries land on the words. The engine logs its own `ParakeetStt` progress and pass lines
 * with `rtf` and `segment_ms_median`, which is what to read in logcat afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ParakeetDeviceTranscriptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun transcribesAPushedClipWithOrderedCues() {
        val clip = File(context.getExternalFilesDir(null), FIXTURE_NAME)
        assumeTrue("Push a $FIXTURE_NAME clip onto the device first, see the class comment", clip.isFile)

        val model = ParakeetModel(context, Lazy { OkHttpClient() })
        // The real file, not a presence check: a wrong path or a truncated bundle is exactly the
        // failure this test exists to catch, and the message has to name where it looked and how
        // many bytes it found.
        val bundle = model.file()
        assertEquals("The bundle belongs at ${bundle.absolutePath}", ParakeetModel.FILE_NAME, bundle.name)
        assertEquals(
            "The bundle at ${bundle.absolutePath} is not the whole ${ParakeetModel.EXPECTED_BYTES} bytes",
            ParakeetModel.EXPECTED_BYTES,
            bundle.length(),
        )
        Log.i(TAG, "Bundle ${bundle.name}: ${bundle.length()} bytes at ${bundle.absolutePath}")

        val transcriber = ParakeetEpisodeTranscriber(
            context = context,
            model = model,
        )
        // The real length, not a constant: a fixture swapped for a shorter clip would otherwise make
        // the rtf in the log a fiction, which is the one number this test exists to report.
        val clipMs = durationOf(clip)
        assumeTrue("Could not read the duration of ${clip.absolutePath}", clipMs > 0L)

        val startedAt = SystemClock.elapsedRealtime()
        val result = runBlocking {
            transcriber.transcribe(
                TranscriptRequest(
                    episodeUuid = "parakeet-device-test",
                    source = TranscriptSource.Downloaded(clip),
                    durationMs = clipMs,
                    podcastLanguage = "en",
                ),
            )
        }
        val wallMs = SystemClock.elapsedRealtime() - startedAt

        result as TranscriptResult.Success
        assertTrue("The engine returned no cues", result.cues.isNotEmpty())
        assertEquals(ParakeetModel.version, result.engine)

        var previousEnd = 0L
        result.cues.forEach { cue ->
            assertTrue("A cue starts before the previous one ends: $cue", cue.startMs >= previousEnd)
            assertTrue("A cue ends before it starts: $cue", cue.endMs > cue.startMs)
            assertTrue("A cue starts past the clip: $cue", cue.startMs < clipMs)
            previousEnd = cue.endMs
        }

        // A cue is one sentence now, so a single cue covering most of the clip means the sentence
        // assembly failed and the timings are worthless.
        val longestCueMs = result.cues.maxOf { it.endMs - it.startMs }
        assertTrue("The longest cue covers $longestCueMs ms of a $clipMs ms clip", longestCueMs < clipMs / 2)

        Log.i(
            TAG,
            "Device pass: ${result.cues.size} cues, ${wallMs}ms wall for $clipMs ms of audio " +
                "(rtf=${ParakeetStats.formatRtf(ParakeetStats.rtf(clipMs, wallMs))}), engine=${result.engine}",
        )
        result.cues.forEach { cue ->
            Log.i(TAG, "[${cue.startMs}..${cue.endMs}] ${cue.text}")
        }
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
