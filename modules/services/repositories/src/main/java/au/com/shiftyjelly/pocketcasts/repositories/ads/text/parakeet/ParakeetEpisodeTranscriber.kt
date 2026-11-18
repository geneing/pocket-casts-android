package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet

import android.content.Context
import android.os.Build
import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.AudioFeed
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.EpisodeTranscriber
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.ModelDownload
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.ModelDownloadProgress
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptAvailability
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptCue
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptRequest
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptResult
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptSource
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.downloadAudioStream
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert.ParakeetBackend
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert.ParakeetMel
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert.ParakeetRecognizer
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert.ParakeetTokenizer
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert.ParakeetWindowBuffer
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert.ParakeetWords
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * On-device transcription of Parakeet TDT 0.6B v3 through LiteRT.
 *
 * One multilingual graph replaces the previous engine's per-language models, so there is exactly one
 * recogniser per pass and no language-specific loading: the episode's language only decides whether
 * the pass runs at all. The graph and its tokenizer are expected to be on the device; a download is
 * the fallback for when they are missing, and it is refused on a metered network.
 *
 * Parakeet is not a streaming model: one graph run covers a fixed 5 s of audio, so the pass slices
 * the episode into 5 s windows that step forward 3 s at a time. The 2 s overlap is what lets
 * [ParakeetWords] settle the seam; without it a word spoken across a boundary would be cut in two.
 * The audio arrives already mono at a constant 16 kHz from [AudioFeed], which is the rate the mel
 * front end expects, and every window's words are reported in the pass's clock, so a resumed pass
 * adds its origin once and reports episode time from then on.
 *
 * Everything the engine needs from the rest of the app arrives in the [TranscriptRequest]: no
 * database lookups happen here, which is what lets a worker, a benchmark or a test run a pass with
 * only a file.
 */
@Singleton
class ParakeetEpisodeTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
    private val model: ParakeetModel,
) : EpisodeTranscriber {

    /**
     * Which hardware the recogniser runs on. The preferred backend by default; public so the device
     * benchmark can pin each of the three and measure the same pipeline.
     */
    var backend: ParakeetBackend = ParakeetBackend.preferred()

    /**
     * The analysis identity: the engine, the exact graph and the cue rules, all in one string that
     * [ParakeetModel] owns. One model covers every allowed language, so there is no per-language tag
     * to fold in; any change invalidates every stored transcript, which is the point.
     */
    override val version: String get() = ParakeetModel.version

    /**
     * The client for the rare streamed-episode pass. The model owns the downloads client and does
     * not expose it, so this one is only ever used for the audio file itself.
     */
    private val httpClient by lazy { OkHttpClient() }

    /**
     * LiteRT declares API 24 as its floor, and the app installs on that floor too, so the runtime
     * gate agrees with the manifest instead of asking for more than the platform the app ships on.
     */
    override fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    override fun availability(podcastLanguage: String?): TranscriptAvailability {
        if (!isAvailable()) return TranscriptAvailability.EngineUnavailable
        if (parakeetLanguage(podcastLanguage) == null) return TranscriptAvailability.UnsupportedLanguage
        // A presence check is two file lookups; it is what keeps a private build with the files
        // already pushed from ever touching the network.
        if (model.isPresent(backend)) return TranscriptAvailability.Ready
        return if (model.canDownload()) {
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
            return TranscriptResult.Unavailable("parakeet needs Android ${Build.VERSION_CODES.N}+")
        }
        val language = parakeetLanguage(request.podcastLanguage) ?: return TranscriptResult.Unavailable(
            "no parakeet model for podcast language '${request.podcastLanguage}'",
        )
        when (availability(request.podcastLanguage)) {
            TranscriptAvailability.Ready -> Unit

            TranscriptAvailability.ModelNeedsUnmeteredNetwork -> return TranscriptResult.Unavailable(
                "${modelMissingMessage()}; waiting for an unmetered network",
            )

            TranscriptAvailability.UnsupportedLanguage -> return TranscriptResult.Unavailable(
                "no parakeet model for podcast language '${request.podcastLanguage}'",
            )

            TranscriptAvailability.EngineUnavailable -> return TranscriptResult.Unavailable(
                "parakeet is unavailable on this device",
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                // One presence check happens inside ensureDownloaded before any request, so an
                // installed model never costs a byte of traffic.
                val download = model.ensureDownloaded(backend, onModelProgress)
                if (download is ModelDownload.NeedsUnmeteredNetwork) {
                    // The "unmetered" wording is what makes OnDeviceTranscriptGenerator defer the
                    // episode instead of retrying it: the download is not a failure, it is a wait.
                    return@withContext TranscriptResult.Unavailable(
                        "${modelMissingMessage()}; waiting for an unmetered network",
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
                LogBuffer.e(ParakeetStats.TAG, e, "Parakeet could not transcribe ${request.episodeUuid}")
                TranscriptResult.Failed(
                    "parakeet failed for $language at ${model.file(backend).absolutePath}: " +
                        (e.message ?: e.javaClass.simpleName),
                )
            }
        }
    }

    /**
     * Runs one pass over [file], a 5 s window at a time.
     *
     * The recogniser, the mel front end and the tokenizer are created once per pass: opening the
     * graph is the slow part (a second or more on a cold process) and redoing it per window would
     * dominate everything else. Window words arrive in pass time, which is what the cue conversion
     * expects; [ParakeetWords] only releases a word once a later window has started after it.
     */
    private suspend fun transcribeAudio(
        request: TranscriptRequest,
        language: String,
        file: File,
        download: ModelDownload,
        onPartialCues: suspend (List<TranscriptCue>) -> Unit,
    ): TranscriptResult {
        val job = currentCoroutineContext()[Job]
        val deadlineAt = SystemClock.elapsedRealtime() + TRANSCRIBE_BUDGET_MS
        val isActive = { job?.isActive == true && SystemClock.elapsedRealtime() < deadlineAt }

        /**
         * Final words keyed by their start time. Which words a window reports depends on how much
         * audio it was given; keying on the start makes a word that two windows both recognise one
         * entry instead of two, and it is the map the cue conversion reads.
         */
        val segments = LinkedHashMap<Long, RawSegment>()
        val passStartedAt = SystemClock.elapsedRealtime()
        var originMs = 0L
        var fedAudioMs = 0L
        var failure: Throwable? = null
        var lastPublishAt = 0L
        var lastProgressAt = 0L
        var melMs = 0L
        var encodeMs = 0L
        var decodeMs = 0L
        var windows = 0
        var reportedFirstWindow = false

        val progress = Channel<List<TranscriptCue>>(Channel.CONFLATED)

        /**
         * The words as cues in the episode timeline. [ParakeetCues] adds [originMs] and clamps to
         * the episode, so a resumed pass reports episode time even though it started at a keyframe
         * before [TranscriptRequest.startMs].
         */
        fun snapshotCues(): List<TranscriptCue> = ParakeetCues.toCues(segments.values.toList(), originMs, request.durationMs)

        /**
         * One line every [PROGRESS_LOG_INTERVAL_MS] of wall time, never more: a pass runs dozens of
         * graph runs per minute, and without the throttle this would bury every other log line.
         * With it, a stall shows up as a gap between two progress lines while the audio clock is
         * still moving.
         */
        fun logProgress(now: Long) {
            if (now - lastProgressAt < PROGRESS_LOG_INTERVAL_MS) return
            lastProgressAt = now
            LogBuffer.i(
                ParakeetStats.TAG,
                "$language " + ParakeetStats.progress(
                    chars = segments.values.sumOf { it.text.length },
                    atMs = segments.values.maxOfOrNull { it.endMs } ?: 0L,
                    elapsedMs = now - passStartedAt,
                    fedAudioMs = fedAudioMs,
                    segments = segments.size,
                ),
            )
        }

        /**
         * Publishes the transcript so far, at most every [PUBLISH_INTERVAL_MS]. Rebuilding the cue
         * list and writing it to the store costs far more than a window of audio, and detection
         * batches its own work by cues and minutes, so a finer interval would only spend CPU the
         * recogniser needs.
         */
        fun publish() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPublishAt < PUBLISH_INTERVAL_MS) return
            lastPublishAt = now
            progress.trySend(snapshotCues())
            logProgress(now)
        }

        val mel = ParakeetMel()
        val tokenizer = ParakeetTokenizer.parse(model.tokenizerFile().readText())
        val recognizer = ParakeetRecognizer(
            context = context,
            encodeFile = model.file(backend),
            // The NPU graph cannot decode: its compiled ops run nowhere else, and its per-token
            // dispatch is slower than the CPU for the decoder's tiny sequential steps.
            decodeFile = if (backend == ParakeetBackend.NPU) model.file(ParakeetBackend.CPU) else model.file(backend),
            backend = backend,
        )
        val words = ParakeetWords(tokenizer)
        LogBuffer.i(
            ParakeetStats.TAG,
            "$language start: ${file.name} ${ParakeetStats.duration(request.durationMs)} " +
                "from ${request.startMs}ms on ${backend.label}, graph opened in ${recognizer.loadMs}ms",
        )

        val feed: AudioFeed.Result
        try {
            coroutineScope {
                // Persisting a cue is milliseconds of file IO; handing it to its own coroutine keeps
                // the recogniser fed while the transcript and its queue rows are written.
                val writer = launch(Dispatchers.IO) {
                    for (partial in progress) onPartialCues(partial)
                }
                try {
                    val buffer = ParakeetWindowBuffer { startMs, samples ->
                        if (failure == null) {
                            try {
                                val melStartedAt = SystemClock.elapsedRealtime()
                                val features = mel.process(samples)
                                melMs += SystemClock.elapsedRealtime() - melStartedAt
                                if (!reportedFirstWindow) {
                                    reportedFirstWindow = true
                                    Timber.tag(ParakeetStats.TAG).d(
                                        "first window: mel ${features.size} floats " +
                                            "min=${features.minOrNull()} max=${features.maxOrNull()} " +
                                            "mean=${features.average()}",
                                    )
                                }
                                val window = recognizer.transcribe(features)
                                encodeMs += window.encodeMs
                                decodeMs += window.decodeMs
                                windows++
                                for (segment in words.accept(startMs, window.tokens)) {
                                    segments[segment.startMs] = segment
                                }
                                publish()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failure = e
                            }
                        }
                    }
                    feed = AudioFeed.decode(
                        file = file,
                        startPositionMs = request.startMs,
                        isActive = isActive,
                        onOrigin = { origin -> originMs = origin },
                    ) { samples, _, positionMs ->
                        fedAudioMs = maxOf(fedAudioMs, positionMs + samples.size * 1000L / AudioFeed.TARGET_SAMPLE_RATE)
                        buffer.accept(samples)
                    }
                    // The tail of the episode is a window too: it is zero padded to 5 s, and its
                    // words are the ones no later window can confirm for us.
                    buffer.finish()
                    for (segment in words.finish()) segments[segment.startMs] = segment
                } finally {
                    // Closing releases the native session; the flush above has already run, so the
                    // snapshot taken next includes its words.
                    runCatching { recognizer.close() }
                    progress.trySend(snapshotCues())
                    progress.close()
                    writer.join()
                }
            }
        } finally {
            progress.close()
        }

        val error = failure
        val wallMs = SystemClock.elapsedRealtime() - passStartedAt
        val cues = snapshotCues()
        val pass = ParakeetPass(
            modelLoadMs = recognizer.loadMs,
            audioMs = feed.audioMs,
            wallMs = wallMs,
            segments = segments.size,
            cues = cues.size,
            segmentMsMedian = ParakeetStats.median(segments.values.map { it.endMs - it.startMs }),
            modelDownloadMs = (download as? ModelDownload.Downloaded)?.wallMs,
            modelBytes = (download as? ModelDownload.Downloaded)?.bytes,
            backend = backend.label,
            windows = windows,
            melMs = melMs,
            encodeMs = encodeMs,
            decodeMs = decodeMs,
        )
        // Logged for a failed pass too: the numbers are the only way to tell a wedged device from a
        // slow one, and a failure is exactly when they are wanted.
        LogBuffer.i(ParakeetStats.TAG, "$language ${ParakeetStats.logLine(pass)}")

        if (error != null) {
            // Cues already written stay: the queue resumes after the last one, and the transcript
            // tab can show what was recognised instead of nothing.
            return TranscriptResult.Failed("recognizer error: ${error.message ?: error.javaClass.simpleName}")
        }
        // The job being cancelled is not a failure, it is the caller giving up; the finally above
        // has already released everything.
        currentCoroutineContext().ensureActive()

        if (!feed.completed) {
            // "stopped_by_caller" covers both a cancelled job and an expired budget, and the two mean
            // different things: one is us giving up, the other is the pass not fitting its limit.
            val budgetExpired = SystemClock.elapsedRealtime() >= deadlineAt
            if (budgetExpired) {
                // The estimate is what says whether the budget or the device is the problem: a
                // wedged pass has an rtf near zero, a slow one still has a measurable one.
                LogBuffer.i(
                    ParakeetStats.TAG,
                    "$language budget of ${TRANSCRIBE_BUDGET_MS / 60_000} minutes expired at " +
                        "rtf=${ParakeetStats.formatRtf(ParakeetStats.rtf(feed.audioMs, wallMs))}",
                )
            }
            return TranscriptResult.Failed(
                "speech recognition stopped after ${feed.audioMs}ms (${feed.stopReason})" +
                    if (budgetExpired) ", budget of ${TRANSCRIBE_BUDGET_MS / 60_000} minutes expired" else "",
            )
        }
        if (cues.isEmpty()) return TranscriptResult.Failed("recognizer returned no speech")
        return TranscriptResult.Success(cues, version)
    }

    /**
     * Fetches a streamed episode into the cache so it can be decoded like a download. The file is
     * deleted after the pass; [STALE_STREAM_MS] clears anything a killed process left behind.
     */
    private fun fetchStream(episodeUuid: String, url: String): File? {
        val directory = File(context.cacheDir, STREAM_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            LogBuffer.e(ParakeetStats.TAG, "Could not create the stream audio cache directory")
            return null
        }
        val cutoff = System.currentTimeMillis() - STALE_STREAM_MS
        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) runCatching { file.delete() }
        }
        val target = File(directory, "$episodeUuid.audio")
        val startedAt = System.currentTimeMillis()
        val file = downloadAudioStream(httpClient, url, target) ?: return null
        LogBuffer.i(
            ParakeetStats.TAG,
            "Fetched ${file.length() / MEGABYTE} MB of stream audio for $episodeUuid in " +
                "${System.currentTimeMillis() - startedAt}ms",
        )
        return file
    }

    private fun modelMissingMessage(): String = "the parakeet speech model is not installed at ${model.file(backend).absolutePath} and was not downloaded"

    private companion object {
        /** How often the partial cue list is published while a pass runs. */
        const val PUBLISH_INTERVAL_MS = 2_000L

        /** How often a progress line is written while a pass runs. */
        const val PROGRESS_LOG_INTERVAL_MS = 5_000L

        /** How much of a segment's text the per-segment debug line carries. */
        const val SEGMENT_LOG_CHARS = 96

        /**
         * How long one pass may take, whatever the source. Parakeet measured 10x real time on a
         * short clip even on the CPU, so this is not a working limit: it only keeps a wedged pass
         * from holding the worker forever.
         */
        const val TRANSCRIBE_BUDGET_MS = 45 * 60_000L

        const val MEGABYTE = 1024L * 1024L

        /** Cache subdirectory of fetched stream audio; deleted after each transcription pass. */
        const val STREAM_DIRECTORY = "parakeet-audio"

        /** A fetched stream older than this is left over from a killed process and can go. */
        const val STALE_STREAM_MS = 24 * 60 * 60 * 1000L
    }
}

/**
 * The podcast language, if this engine covers it.
 *
 * Parakeet picks the language itself from the audio and reports it in the transcript, but running it
 * over audio it does not know would produce confident nonsense, which is worse than leaving the
 * episode without on-device detection.
 */
internal fun parakeetLanguage(podcastLanguage: String?): String? {
    val normalised = podcastLanguage?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return ENGLISH_LANGUAGE
    val primary = normalised.substringBefore('-').substringBefore('_')
    return primary.takeIf { it in PARAKEET_LANGUAGES }
}

/** The languages `parakeet_tdt_0.6b_v3` covers; anything else is skipped rather than guessed. */
private val PARAKEET_LANGUAGES = setOf(
    "bg",
    "hr",
    "cs",
    "da",
    "nl",
    "el",
    "en",
    "et",
    "fi",
    "fr",
    "de",
    "hu",
    "it",
    "lv",
    "lt",
    "mt",
    "pl",
    "pt",
    "ro",
    "sk",
    "sl",
    "es",
    "sv",
    "ru",
    "uk",
)

/** What a podcast that declares no language is transcribed as. */
private const val ENGLISH_LANGUAGE = "en"

/** A playlist URL can never be decoded as audio, whatever the flag on the source says. */
private fun String.isPlaylistUrl(): Boolean = substringBefore('?').lowercase().endsWith(".m3u8")
