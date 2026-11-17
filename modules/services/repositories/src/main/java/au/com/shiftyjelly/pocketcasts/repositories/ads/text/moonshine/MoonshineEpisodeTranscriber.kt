package au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine

import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.TranscriptEventListener
import ai.moonshine.voice.TranscriptLine
import android.content.Context
import android.os.Build
import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.EpisodeTranscriber
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.ModelDownloadProgress
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptAvailability
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptCue
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptRequest
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptResult
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptSource
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.downloadAudioStream
import au.com.shiftyjelly.pocketcasts.servers.di.Downloads
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * On-device transcription through Moonshine's streaming API.
 *
 * One [Transcriber] is loaded for the episode's language, one stream is created at t=0 and the
 * decoded audio is fed to it chunk by chunk, so line and word times come back in the episode's own
 * timeline. That replaces ML Kit's one-shot recognizer, which had no clock at all and forced cue
 * times to be reconstructed from audio energy.
 *
 * Everything the engine needs from the rest of the app arrives in the [TranscriptRequest]: no
 * database lookups happen here, which is what lets a worker, a benchmark or a test run a pass with
 * only a file. The model is expected to be on the device; a download is only the fallback for when
 * it is missing, and it is refused on a metered network.
 *
 * Rejected: `transcribeWithoutStreaming`. It needs the whole episode as one `float[]` (about 230 MB
 * for an hour), gives no progress and no cancellation point, and cannot use the streaming models
 * this app ships.
 */
@Singleton
class MoonshineEpisodeTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: MoonshineModels,
    @Downloads private val httpClient: Lazy<OkHttpClient>,
) : EpisodeTranscriber {

    /**
     * The analysis identity: engine, tiny-streaming arch and the word-timestamp policy of each
     * allowed language. Bumped for every language as a whole rather than per language, because it
     * is stamped before the episode's language is known at read time.
     */
    override val version: String = MoonshineModels.engineVersion

    /**
     * API 26 is the AAR's floor. Nothing here touches the microphone, so no permission is
     * involved: the AAR declares `RECORD_AUDIO` for its live-microphone API, and the app strips it
     * from the merged manifest.
     */
    override fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    override fun availability(podcastLanguage: String?): TranscriptAvailability {
        if (!isAvailable()) return TranscriptAvailability.EngineUnavailable
        val language = speechLanguage(podcastLanguage) ?: return TranscriptAvailability.UnsupportedLanguage
        // A presence check is a few file lookups; it is what keeps a private build with the models
        // already pushed from ever touching the network.
        if (models.isPresent(language)) return TranscriptAvailability.Ready
        return if (models.canDownload()) {
            TranscriptAvailability.Ready
        } else {
            TranscriptAvailability.ModelNeedsUnmeteredNetwork
        }
    }

    override suspend fun transcribe(
        request: TranscriptRequest,
        onModelProgress: (ModelDownloadProgress) -> Unit,
        onPartialCues: suspend (List<TranscriptCue>) -> Unit,
    ): TranscriptResult {
        val source = request.source
        if (source is TranscriptSource.Downloaded && !source.file.isFile) {
            return TranscriptResult.Failed("missing audio file ${source.file.absolutePath}")
        }
        if (source is TranscriptSource.Stream && (source.isHls || source.url.isPlaylistUrl())) {
            // A plain GET of a playlist stores text that no decoder can open; this is a known hole
            // rather than something to paper over with a "transcript" of a playlist file.
            return TranscriptResult.Unavailable("hls stream")
        }
        if (!isAvailable()) {
            return TranscriptResult.Unavailable("moonshine needs Android ${Build.VERSION_CODES.O}+")
        }
        val language = speechLanguage(request.podcastLanguage) ?: return TranscriptResult.Unavailable(
            "no speech model for podcast language '${request.podcastLanguage}'",
        )
        when (availability(request.podcastLanguage)) {
            TranscriptAvailability.Ready -> Unit

            TranscriptAvailability.ModelNeedsUnmeteredNetwork -> return TranscriptResult.Unavailable(
                "${modelMissingMessage(language, models.directory(language))}; waiting for an unmetered network",
            )

            TranscriptAvailability.UnsupportedLanguage -> return TranscriptResult.Unavailable(
                "no speech model for podcast language '${request.podcastLanguage}'",
            )

            TranscriptAvailability.EngineUnavailable -> return TranscriptResult.Unavailable("moonshine is unavailable on this device")
        }

        return withContext(Dispatchers.IO) {
            try {
                // One presence check happens inside ensureDownloaded before any request, so an
                // installed model never costs a byte of traffic.
                val download = models.ensureDownloaded(language, onModelProgress)
                if (download is ModelDownload.NeedsUnmeteredNetwork) {
                    return@withContext TranscriptResult.Unavailable(
                        "${modelMissingMessage(language, models.directory(language))}; waiting for an unmetered network",
                    )
                }
                val local = when (source) {
                    is TranscriptSource.Downloaded -> source.file

                    is TranscriptSource.Stream -> fetchStream(request.episodeUuid, source.url)
                        ?: return@withContext TranscriptResult.Failed("could not download the episode audio")
                }
                val temporary = source is TranscriptSource.Stream
                try {
                    transcribeAudio(request, language, local, download, onPartialCues)
                } finally {
                    if (temporary) runCatching { local.delete() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBuffer.e(TAG, e, "Moonshine could not transcribe ${request.episodeUuid}")
                TranscriptResult.Failed(
                    "moonshine failed for ${language.code} at ${models.directory(language).absolutePath}: " +
                        (e.message ?: e.javaClass.simpleName),
                )
            }
        }
    }

    /**
     * Runs one streaming session over [file]. The stream is always created at t=0 and never
     * restarted, so the library's cumulative positions are the episode's own.
     */
    private suspend fun transcribeAudio(
        request: TranscriptRequest,
        language: MoonshineLanguage,
        file: File,
        download: ModelDownload,
        onPartialCues: suspend (List<TranscriptCue>) -> Unit,
    ): TranscriptResult {
        val job = currentCoroutineContext()[Job]
        val deadlineAt = SystemClock.elapsedRealtime() + TRANSCRIBE_BUDGET_MS
        val isActive = { job?.isActive == true && SystemClock.elapsedRealtime() < deadlineAt }

        val completedLines = Collections.synchronizedList(ArrayList<TranscriptLine>())
        val failure = AtomicReference<Throwable?>(null)
        val passStartedAt = SystemClock.elapsedRealtime()
        val fedAudioMs = AtomicLong(0L)
        val originMs = AtomicLong(0L)
        var lastProgressAt = 0L
        var completedChars = 0

        /**
         * One line per completed cue, and at most one per [PROGRESS_LOG_INTERVAL_MS] while a cue is
         * still being written. The recogniser is called every half second, so without the throttle
         * this would bury every other log line; with it, a stall shows up as a gap between two
         * progress lines with the audio clock still moving.
         */
        fun logProgress(line: TranscriptLine?, force: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastProgressAt < PROGRESS_LOG_INTERVAL_MS) return
            lastProgressAt = now
            val partialChars = line?.text?.length ?: 0
            val lineStartMs = ((line?.startTime ?: 0f).toDouble() * 1000.0).roundToLong()
            LogBuffer.i(
                MoonshineStats.TAG,
                "${language.code} " + MoonshineStats.progress(
                    chars = completedChars + partialChars,
                    lineStartMs = lineStartMs,
                    elapsedMs = now - passStartedAt,
                    fedAudioMs = fedAudioMs.get(),
                ),
            )
        }

        /**
         * The completed lines as cues in the episode timeline. A resumed pass starts at a keyframe
         * before [TranscriptRequest.startMs], so the recognizer's own clock starts at [originMs]
         * rather than at zero and every cue has to be moved by it.
         */
        fun snapshotCues(): List<TranscriptCue> {
            val lines = synchronized(completedLines) { completedLines.toList() }
            val origin = originMs.get()
            val relativeDuration = if (request.durationMs > 0) (request.durationMs - origin).coerceAtLeast(0L) else 0L
            return cues(lines, relativeDuration).map { cue ->
                cue.copy(startMs = cue.startMs + origin, endMs = cue.endMs + origin)
            }
        }

        val progress = Channel<List<TranscriptCue>>(Channel.CONFLATED)

        val listener = object : TranscriptEventListener() {
            override fun onLineCompleted(event: TranscriptEvent.LineCompleted) {
                event.line?.let { line ->
                    completedLines.add(line)
                    completedChars += line.text?.length ?: 0
                }
                progress.trySend(snapshotCues())
                logProgress(event.line, force = true)
            }

            override fun onLineTextChanged(event: TranscriptEvent.LineTextChanged) {
                logProgress(event.line, force = false)
            }

            override fun onLineStarted(event: TranscriptEvent.LineStarted) {
                logProgress(event.line, force = false)
            }

            override fun onError(event: TranscriptEvent.Error) {
                failure.compareAndSet(null, event.cause)
            }
        }

        val directory = models.directory(language)
        val options = buildList {
            // English only: the Spanish bundle ships no attention decoder, and asking for word
            // timestamps there only produces a warning and an empty word list.
            if (language.wordTimestamps) add(TranscriberOption(OPTION_WORD_TIMESTAMPS, "true"))
        }
        val transcriber = Transcriber(options)
        var streamHandle = -1
        var loadMs = 0L
        val feed: MoonshineAudioFeed.Result
        try {
            coroutineScope {
                // Persisting a cue is milliseconds of file IO; handing it to its own coroutine keeps
                // the decoder feeding audio while the transcript and its queue rows are written.
                val writer = launch(Dispatchers.IO) {
                    for (partial in progress) onPartialCues(partial)
                }
                try {
                    val loadStartedAt = SystemClock.elapsedRealtime()
                    transcriber.loadFromFiles(directory.absolutePath, language.arch)
                    loadMs = SystemClock.elapsedRealtime() - loadStartedAt
                    Timber.tag(TAG).d("Loaded %s model from %s in %dms", language.code, directory, loadMs)
                    transcriber.setUpdateInterval(UPDATE_INTERVAL_SECONDS)
                    transcriber.addListener { event -> event.accept(listener) }
                    streamHandle = transcriber.createStream()
                    transcriber.startStream(streamHandle)
                    LogBuffer.i(
                        MoonshineStats.TAG,
                        "${language.code} start: ${file.name} ${MoonshineStats.duration(request.durationMs)} " +
                            "from ${request.startMs}ms, model ${MoonshineStats.duration(loadMs)} to load",
                    )
                    feed = MoonshineAudioFeed.decode(
                        file,
                        startPositionMs = request.startMs,
                        isActive = isActive,
                        onOrigin = { origin -> originMs.set(origin) },
                    ) { samples, sampleRate, positionMs ->
                        fedAudioMs.set(maxOf(positionMs, fedAudioMs.get()))
                        transcriber.addAudioToStream(streamHandle, samples, sampleRate)
                    }
                } finally {
                    // Order matters: stopping flushes the last partial line into a completed one,
                    // freeing releases the stream, and closing releases the model sessions.
                    if (streamHandle >= 0) {
                        runCatching { transcriber.stopStream(streamHandle) }
                        runCatching { transcriber.freeStream(streamHandle) }
                    }
                    runCatching { transcriber.close() }
                    // The flush above can complete one last line, so the snapshot is taken after it.
                    progress.trySend(snapshotCues())
                    progress.close()
                    writer.join()
                }
            }
        } finally {
            progress.close()
        }

        val error = failure.get()
        if (error != null) {
            // Cues already written stay: the queue resumes after the last one, and the transcript
            // tab can show what was recognised instead of nothing.
            return TranscriptResult.Failed("recognizer error: ${error.message ?: error.javaClass.simpleName}")
        }
        // The job being cancelled is not a failure, it is the caller giving up; the finally above
        // has already released everything.
        currentCoroutineContext().ensureActive()

        val lines = synchronized(completedLines) { completedLines.toList() }
        if (!feed.completed) {
            // "stopped_by_caller" covers both a cancelled job and an expired budget, and the two mean
            // different things: one is us giving up, the other is the pass not fitting its limit.
            val budgetExpired = SystemClock.elapsedRealtime() >= deadlineAt
            return TranscriptResult.Failed(
                "speech recognition stopped after ${feed.audioMs}ms (${feed.stopReason})" +
                    if (budgetExpired) ", budget of ${TRANSCRIBE_BUDGET_MS / 60_000} minutes expired" else "",
            )
        }
        val cues = snapshotCues()
        val lineStats = MoonshineStats.lineStats(lines)
        val pass = MoonshinePass(
            modelLoadMs = loadMs,
            audioMs = feed.audioMs,
            wallMs = SystemClock.elapsedRealtime() - passStartedAt,
            lineStats = lineStats,
            cues = cues.size,
            modelDownloadMs = (download as? ModelDownload.Downloaded)?.wallMs,
            modelBytes = (download as? ModelDownload.Downloaded)?.bytes,
        )
        LogBuffer.i(MoonshineStats.TAG, "Moonshine ${language.code} ${MoonshineStats.logLine(pass)}")

        if (cues.isEmpty()) return TranscriptResult.Failed("recognizer returned no speech")
        return TranscriptResult.Success(cues, language.versionTag)
    }

    /**
     * Fetches a streamed episode into the cache so it can be decoded like a download. The file is
     * deleted after the pass; [STALE_STREAM_MS] clears anything a killed process left behind.
     */
    private fun fetchStream(episodeUuid: String, url: String): File? {
        val directory = File(context.cacheDir, STREAM_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            LogBuffer.e(TAG, "Could not create the stream audio cache directory")
            return null
        }
        val cutoff = System.currentTimeMillis() - STALE_STREAM_MS
        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) runCatching { file.delete() }
        }
        val target = File(directory, "$episodeUuid.audio")
        val startedAt = System.currentTimeMillis()
        val file = downloadAudioStream(httpClient.get(), url, target) ?: return null
        LogBuffer.i(
            TAG,
            "Fetched ${file.length() / MEGABYTE} MB of stream audio for $episodeUuid in " +
                "${System.currentTimeMillis() - startedAt}ms",
        )
        return file
    }

    private companion object {
        const val TAG = "MoonshineStt"

        /** `word_timestamps` is the library's option name; the flag is flipped per language. */
        const val OPTION_WORD_TIMESTAMPS = "word_timestamps"

        /** Half a second, the library's default, made explicit so a change is a decision. */
        const val UPDATE_INTERVAL_SECONDS = 0.5

        /** How often a progress line is written while one cue is still being recognised. */
        const val PROGRESS_LOG_INTERVAL_MS = 5_000L

        /**
         * How long one pass may take, whatever the source. A pass runs at roughly 2.4x realtime on a
         * Pixel 11, so a 40-minute episode needs about 17 minutes and a slower phone needs more; the
         * budget is only there to keep a wedged pass from holding the worker forever.
         *
         * There used to be a two-minute budget for streamed episodes, which no real episode could
         * ever fit: the pass gave up a tenth of the way in and the episode was left without a
         * transcript.
         */
        const val TRANSCRIBE_BUDGET_MS = 45 * 60_000L

        const val MEGABYTE = 1024L * 1024L

        /** Cache subdirectory of fetched stream audio; deleted after each transcription pass. */
        const val STREAM_DIRECTORY = "moonshine-audio"

        /** A fetched stream older than this is left over from a killed process and can go. */
        const val STALE_STREAM_MS = 24 * 60 * 60 * 1000L
    }
}

/** A playlist URL can never be decoded as audio, whatever the flag on the source says. */
private fun String.isPlaylistUrl(): Boolean = substringBefore('?').lowercase().endsWith(".m3u8")
