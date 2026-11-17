package au.com.shiftyjelly.pocketcasts

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptRequest
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptResult
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptSource
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine.MoonshineEpisodeTranscriber
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine.MoonshineLanguage
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine.MoonshineModels
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
 * Runs the real speech engine on the device, over a clip the models and the decoder can both handle.
 *
 * This is the only check that covers what unit tests cannot: that the native library loads on this
 * ABI, that the pushed model is the one the engine expects, that the streaming API accepts our PCM
 * feed, and how fast it all is. Models are installed by
 * `dev/adaware/next/moonshine/install-app.ps1`; the audio clip is copied in by hand, because a
 * usable minute of podcast is too big to keep in the repository:
 *
 * ```
 * adb push clip.mp3 /sdcard/Android/data/au.com.shiftyjelly.pocketcasts.debug/files/moonshine-fixture.mp3
 * ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=au.com.shiftyjelly.pocketcasts.MoonshineDeviceTranscriptionTest
 * ```
 *
 * A clip that contains one ad break is the useful one: the cue times around it show whether the
 * boundaries land on the words. The engine logs its own `MoonshineStt` line with `rtf`, line and
 * word medians, which is what to read in logcat afterwards.
 */
@RunWith(AndroidJUnit4::class)
class MoonshineDeviceTranscriptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun transcribesAPushedClipWithOrderedCues() {
        val clip = File(context.getExternalFilesDir(null), FIXTURE_NAME)
        assumeTrue("Push a $FIXTURE_NAME clip onto the device first, see the class comment", clip.isFile)

        val models = MoonshineModels(context)
        assertTrue(
            "The English model is not installed at ${models.directory(MoonshineLanguage.EN)}",
            models.isPresent(MoonshineLanguage.EN),
        )

        val transcriber = MoonshineEpisodeTranscriber(
            context = context,
            models = models,
            httpClient = Lazy { OkHttpClient() },
        )

        val startedAt = System.currentTimeMillis()
        val result = runBlocking {
            transcriber.transcribe(
                TranscriptRequest(
                    episodeUuid = "moonshine-device-test",
                    source = TranscriptSource.Downloaded(clip),
                    durationMs = CLIP_MS,
                    podcastLanguage = "en",
                ),
            )
        }
        val wallMs = System.currentTimeMillis() - startedAt

        result as TranscriptResult.Success
        assertTrue("The engine returned no cues", result.cues.isNotEmpty())
        assertEquals(MoonshineLanguage.EN.versionTag, result.engine)

        var previousEnd = 0L
        result.cues.forEach { cue ->
            assertTrue("A cue starts before the previous one ends: $cue", cue.startMs >= previousEnd)
            assertTrue("A cue ends before it starts: $cue", cue.endMs > cue.startMs)
            assertTrue("A cue starts past the clip: $cue", cue.startMs < CLIP_MS)
            previousEnd = cue.endMs
        }

        // One cue is one spoken turn, so a single cue spanning most of the clip means the VAD
        // produced nothing usable and the timings are worthless.
        val longestCueMs = result.cues.maxOf { it.endMs - it.startMs }
        assertTrue("The longest cue covers $longestCueMs ms of a $CLIP_MS ms clip", longestCueMs < CLIP_MS / 2)

        Log.i(
            TAG,
            "Device pass: ${result.cues.size} cues, ${wallMs}ms wall for $CLIP_MS ms of audio " +
                "(${"%.2f".format(CLIP_MS.toDouble() / wallMs)}x realtime), engine=${result.engine}",
        )
        result.cues.forEach { cue ->
            Log.i(TAG, "[${cue.startMs}..${cue.endMs}] ${cue.text}")
        }
    }

    private companion object {
        const val TAG = "MoonshineStt"
        const val FIXTURE_NAME = "moonshine-fixture.mp3"

        /** The clip is two minutes long; see the class comment. */
        const val CLIP_MS = 120_000L
    }
}
