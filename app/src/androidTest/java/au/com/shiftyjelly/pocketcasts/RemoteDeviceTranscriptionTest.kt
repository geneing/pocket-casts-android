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
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote.RemoteAsrHealth
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote.RemoteAudioCache
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote.RemoteEpisodeTranscriber
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote.RemoteSttClient
import com.squareup.moshi.Moshi
import dagger.Lazy
import java.io.File
import java.util.Locale
import java.util.Optional
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs a real remote transcription on the device: the chunk splitter, the upload, the provider's
 * words, the timestamps it reports, and the price it charges. Nothing here is a unit test — the
 * splitter is MediaExtractor/MediaMuxer or a frame copy and the answer comes from OpenRouter, which
 * is exactly what cannot be checked on the JVM.
 *
 * It needs a key in the build (`OPENROUTER_API_KEY` or `local.properties` when Gradle runs) and a
 * clip pushed onto the device; without either it is skipped rather than failed:
 *
 * ```
 * adb push clip.mp3 /sdcard/Android/data/au.com.shiftyjelly.pocketcasts.debug/files/parakeet-fixture.mp3
 * ./gradlew :app:connectedDebugProdAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=au.com.shiftyjelly.pocketcasts.RemoteDeviceTranscriptionTest
 * ```
 *
 * A clip longer than one chunk is the useful one: it is the only way the seam offsets are exercised.
 * Push it as `parakeet-fixture.mp3` to exercise the MP3 frame copy, or as `parakeet-fixture.m4a` to
 * exercise the MP4 remux — the two split paths, and only the second needs the platform's muxer.
 * The engine logs an `RemoteAsr` line per chunk with `split=yes` (a copy, no decoder) or
 * `split=no` (re-encoded) and one `pass:` line at the end, which is what to read in logcat after.
 */
@RunWith(AndroidJUnit4::class)
class RemoteDeviceTranscriptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun transcribesAPushedClipRemotely() {
        val clip = FIXTURE_NAMES.map { File(context.getExternalFilesDir(null), it) }.firstOrNull { it.isFile }
        assumeTrue("Push a ${FIXTURE_NAMES.first()} clip onto the device first, see the class comment", clip != null)
        if (clip == null) return
        val clipMs = durationOf(clip)
        assumeTrue("Could not read the duration of ${clip.absolutePath}", clipMs > 0L)

        val result = transcribe(TranscriptSource.Downloaded(clip), clipMs, "remote-asr-device-test")

        // The clip starts with speech and every later chunk has to land where the previous one
        // ended: a cue near the start, a cue near each seam, no long stretch with nothing in it, and
        // no cue after the end are what say the per-chunk origin is being added rather than guessed.
        assertTrue(
            "The first cue starts at ${result.cues.first().startMs}ms, not near the start of the clip",
            result.cues.first().startMs < 5_000L,
        )
        val seams = generateSequence(RemoteEpisodeTranscriber.CHUNK_MS) { it + RemoteEpisodeTranscriber.CHUNK_MS }
            .takeWhile { it < clipMs - SEAM_MARGIN_MS }
            .toList()
        seams.forEach { seam ->
            val near = result.cues.any { it.startMs in (seam - SEAM_MARGIN_MS)..(seam + SEAM_MARGIN_MS) }
            assertTrue("No cue within ${SEAM_MARGIN_MS}ms of the chunk boundary at ${seam}ms", near)
        }
        val maxGapMs = result.cues.zipWithNext { previous, next -> next.startMs - previous.endMs }.maxOrNull() ?: 0L
        assertTrue("A ${maxGapMs}ms gap between cues looks like a lost chunk", maxGapMs < MAX_GAP_MS)
        Log.i(TAG, "Seams checked at $seams, largest cue gap ${maxGapMs}ms")
    }

    @Test
    fun transcribesAStreamedEpisodeAndDeletesTheCopyItFetched() {
        val episodeUuid = "remote-asr-device-test-stream"
        // A tiny public WAV: the stream path has to fetch it, and a WAV takes the encode path, so this
        // covers both the fetch and the chunk encoder.
        val clipMs = 11_000L
        val result = transcribe(TranscriptSource.Stream(STREAM_URL), clipMs, episodeUuid)

        assertTrue("The engine returned no cues for a streamed episode", result.cues.isNotEmpty())
        val leftovers = RemoteAudioCache(context).directory()
            .listFiles()
            ?.filter { it.name.startsWith(episodeUuid) }
            .orEmpty()
        assertTrue("The fetched stream was left on disk: $leftovers", leftovers.isEmpty())
    }

    private fun transcribe(source: TranscriptSource, clipMs: Long, episodeUuid: String): TranscriptResult.Success {
        val key = BuildConfig.OPENROUTER_API_KEY.trim()
        assumeTrue("Build with an OpenRouter key to run the remote pass", key.isNotEmpty())
        val httpClient = OkHttpClient()
        val transcriber = RemoteEpisodeTranscriber(
            cache = RemoteAudioCache(context),
            client = RemoteSttClient(httpClient, Optional.of(key), Moshi.Builder().build(), RemoteAsrHealth(context)),
            downloads = Lazy { httpClient },
        )
        assertTrue("A build with a key should report the engine as available", transcriber.isAvailable())

        val startedAt = SystemClock.elapsedRealtime()
        val result = runBlocking {
            transcriber.transcribe(
                TranscriptRequest(
                    episodeUuid = episodeUuid,
                    source = source,
                    durationMs = clipMs,
                    podcastLanguage = "en",
                ),
            )
        }
        val wallMs = SystemClock.elapsedRealtime() - startedAt

        result as TranscriptResult.Success
        assertTrue("The engine returned no cues", result.cues.isNotEmpty())
        assertEquals(transcriber.version, result.engine)

        // Logged before the assertions: a failing cue is easier to read in logcat with its
        // neighbours around it than from the assertion's single line.
        result.cues.forEach { cue -> Log.i(TAG, "[${cue.startMs}..${cue.endMs}] ${cue.text}") }

        var previousEnd = 0L
        result.cues.forEach { cue ->
            assertTrue("A cue starts before the previous one ends: $cue", cue.startMs >= previousEnd)
            assertTrue("A cue ends before it starts: $cue", cue.endMs > cue.startMs)
            assertTrue("A cue starts past the clip: $cue", cue.startMs < clipMs)
            previousEnd = cue.endMs
        }

        // The endpoint reports a price; a null here means the response shape changed and the closing
        // line of the transcript would silently stop appearing.
        val cost = result.costUsd
        assertTrue("The remote pass reported no cost", cost != null && cost > 0.0)

        Log.i(
            TAG,
            "Remote pass: ${result.cues.size} cues, ${wallMs}ms wall for $clipMs ms of audio, " +
                "cost=${String.format(Locale.US, "\$%.6f", cost)}, engine=${result.engine}",
        )
        return result
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
        const val TAG = "RemoteAsr"

        /**
         * The fixture, in either split path: an mp3 is cut by copying its frames, an m4a by copying
         * its samples through the platform's muxer.
         */
        val FIXTURE_NAMES = listOf("parakeet-fixture.mp3", "parakeet-fixture.m4a")
        const val STREAM_URL = "https://raw.githubusercontent.com/ggerganov/whisper.cpp/master/samples/jfk.wav"

        /** A seam is this far from the middle of the two chunks that share it, either way. */
        const val SEAM_MARGIN_MS = 30_000L

        /** Continuous speech with an ad break never leaves a minute of nothing in this clip. */
        const val MAX_GAP_MS = 60_000L
    }
}
