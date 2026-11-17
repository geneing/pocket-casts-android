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
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriber
import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriberResult
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
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
 * On-device transcription through MediaPipe's audio transcriber running Parakeet TDT 0.6B v3.
 *
 * One multilingual bundle replaces the previous engine's per-language models, so there is exactly
 * one transcriber per pass and no language-specific loading: the episode's language only decides
 * whether the pass runs at all. The bundle is expected to be on the device; a download is the
 * fallback for when it is missing, and it is refused on a metered network.
 *
 * The audio arrives already mono at a constant 16 kHz from [AudioFeed], which is what lets a single
 * stateful session be created with one fixed rate and never be restarted. Segments come back with
 * start and end times relative to the first sample fed, so a resumed pass adds its origin once and
 * reports episode time from then on.
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
     * The analysis identity: the engine, the exact bundle and the cue rules, all in one string that
     * [ParakeetModel] owns. One model covers every allowed language, so there is no per-language tag
     * to fold in; any change invalidates every stored transcript, which is the point.
     */
    override val version: String get() = ParakeetModel.version

    /**
     * The client for the rare streamed-episode pass. The model owns the downloads client and does
     * not expose it, and a streamed episode is one plain GET of one file per pass, so a client of
     * this singleton's own is cheaper than widening the injected graph for that path.
     */
    private val httpClient by lazy { OkHttpClient() }

    /**
     * API 24 is the AAR's declared floor, and the app installs on that floor too, so the runtime
     * gate agrees with the manifest instead of asking for more than the platform the app ships on.
     */
    override fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    override fun availability(podcastLanguage: String?): TranscriptAvailability {
        if (!isAvailable()) return TranscriptAvailability.EngineUnavailable
        if (parakeetLanguage(podcastLanguage) == null) return TranscriptAvailability.UnsupportedLanguage
        // A presence check is one file lookup; it is what keeps a private build with the bundle
        // already pushed from ever touching the network.
        if (model.isPresent()) return TranscriptAvailability.Ready
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
                val download = model.ensureDownloaded(onModelProgress)
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
                    "parakeet failed for $language at ${model.file().absolutePath}: " +
                        (e.message ?: e.javaClass.simpleName),
                )
            }
        }
    }

    /**
     * Runs one transcriber session over [file].
     *
     * The session is created once per pass, at the one sample rate the feed produces, and is fed one
     * chunk at a time; nothing restarts it mid-episode. Segment times are therefore relative to the
     * first sample fed — which is [AudioFeed.Result.originMs] in the container, not the resume point
     * — and the cue conversion adds that origin exactly once.
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
         * Segments keyed by their start time. Which segments a call returns depends on the bundle:
         * a cumulative result re-reports everything it knows (same start, more text), an
         * incremental one only reports what is new. Keying on the start makes both converge on the
         * same map — a re-reported segment replaces its earlier text instead of being stored twice
         * — and the key is also what keeps a segment's debug line from being logged repeatedly.
         *
         * Everything here runs on the calling coroutine: the feed, the transcribe calls and the
         * snapshots, so no synchronisation is needed.
         */
        val segments = LinkedHashMap<Long, RawSegment>()
        val finalised = HashSet<Long>()
        val passStartedAt = SystemClock.elapsedRealtime()
        var originMs = 0L
        var fedAudioMs = 0L
        var modelLoadMs = 0L
        var failure: Throwable? = null
        var lastPublishAt = 0L
        var lastProgressAt = 0L

        val progress = Channel<List<TranscriptCue>>(Channel.CONFLATED)

        /**
         * The segments as cues in the episode timeline. [ParakeetCues] adds [originMs] and clamps to
         * the episode, so a resumed pass reports episode time even though it started at a keyframe
         * before [TranscriptRequest.startMs].
         */
        fun snapshotCues(): List<TranscriptCue> = ParakeetCues.toCues(segments.values.toList(), originMs, request.durationMs)

        /**
         * One line every [PROGRESS_LOG_INTERVAL_MS] of wall time, never more: the pass calls into
         * the recognizer for every chunk, and without the throttle this would bury every other log
         * line. With it, a stall shows up as a gap between two progress lines while the audio clock
         * is still moving.
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
         * list and writing it to the store costs far more than a chunk of audio, and detection
         * batches its own work by cues and minutes, so a finer interval would only spend CPU the
         * recognizer needs.
         */
        fun publish() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastPublishAt < PUBLISH_INTERVAL_MS) return
            lastPublishAt = now
            progress.trySend(snapshotCues())
            logProgress(now)
        }

        /** Collects one call's segments into [segments] and logs the ones that just became final. */
        fun addSegments(result: AudioTranscriberResult?) {
            result ?: return
            for (segment in result.segments()) {
                // MediaPipe's Segment accessors are plain methods, not getters, so none of these is
                // a Kotlin property except isFinal.
                val text = segment.transcript()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val fromMs = segment.fromTimestampMs()
                val raw = RawSegment(
                    text = text,
                    startMs = fromMs,
                    endMs = segment.toTimestampMs(),
                    isFinal = segment.isFinal,
                )
                segments[fromMs] = raw
                if (raw.isFinal && finalised.add(fromMs)) {
                    Timber.tag(ParakeetStats.TAG).d(
                        "[%d..%d] %s",
                        raw.startMs,
                        raw.endMs,
                        raw.text.take(SEGMENT_LOG_CHARS),
                    )
                }
            }
        }

        val transcriber = createTranscriber()
        val feed: AudioFeed.Result
        try {
            coroutineScope {
                // Persisting a cue is milliseconds of file IO; handing it to its own coroutine keeps
                // the transcriber fed while the transcript and its queue rows are written.
                val writer = launch(Dispatchers.IO) {
                    for (partial in progress) onPartialCues(partial)
                }
                try {
                    // MediaPipe compiles the bundle into its cache directory on first use, so this
                    // is the slow part of the first pass on a device and worth its own number. It is
                    // near zero on later passes, when the compiled artifact is already cached.
                    val loadStartedAt = SystemClock.elapsedRealtime()
                    transcriber.prewarm()
                    modelLoadMs = SystemClock.elapsedRealtime() - loadStartedAt
                    LogBuffer.i(
                        ParakeetStats.TAG,
                        "$language start: ${file.name} ${ParakeetStats.duration(request.durationMs)} " +
                            "from ${request.startMs}ms, engine warmed in ${modelLoadMs}ms",
                    )
                    feed = AudioFeed.decode(
                        file = file,
                        startPositionMs = request.startMs,
                        isActive = isActive,
                        onOrigin = { origin -> originMs = origin },
                    ) { samples, sampleRate, positionMs ->
                        fedAudioMs = maxOf(fedAudioMs, positionMs + samples.size * 1000L / sampleRate)
                        if (failure == null) {
                            // One call per chunk, as the task expects; interim results are off, so
                            // everything it returns is a segment that can already be stored. The
                            // second argument is endOfStream (a Java method, so positional): false
                            // here because the pass ends with finishStream() below.
                            try {
                                addSegments(transcriber.transcribe(samples, false))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failure = e
                            }
                            publish()
                        }
                    }
                    // The last chunk can leave a segment open; finishStream flushes it, and the
                    // flushed segment counts like any other.
                    try {
                        addSegments(transcriber.finishStream())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (failure == null) failure = e
                    }
                } finally {
                    // Closing releases the native session; the flush above has already run, so the
                    // snapshot taken next includes its segments.
                    runCatching { transcriber.close() }
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
            modelLoadMs = modelLoadMs,
            audioMs = feed.audioMs,
            wallMs = wallMs,
            segments = segments.size,
            cues = cues.size,
            segmentMsMedian = ParakeetStats.median(segments.values.map { it.endMs - it.startMs }),
            modelDownloadMs = (download as? ModelDownload.Downloaded)?.wallMs,
            modelBytes = (download as? ModelDownload.Downloaded)?.bytes,
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
     * Opens the bundle, preferring the architecture the bundle itself declares.
     *
     * `AUTO` asks MediaPipe to detect the family from the bundle's header, which is the right answer
     * for the LiteRT-LM Parakeet file this app ships; `OMNI` is the fallback for open-weights
     * bundles, so one the detection refuses can still run instead of leaving the episode without a
     * transcript. Which one worked is logged because the two can differ in speed on the same phone.
     */
    private fun createTranscriber(): AudioTranscriber {
        try {
            return AudioTranscriber.createFromOptions(
                context,
                options(AudioTranscriber.ModelArchitecture.AUTO),
            ).also { LogBuffer.i(ParakeetStats.TAG, "Opened ${model.file().name} with the AUTO architecture") }
        } catch (e: Exception) {
            LogBuffer.w(
                ParakeetStats.TAG,
                "AUTO architecture could not open ${model.file().name}: ${e.message}; retrying with OMNI",
            )
        }
        return AudioTranscriber.createFromOptions(
            context,
            options(AudioTranscriber.ModelArchitecture.OMNI),
        ).also { LogBuffer.i(ParakeetStats.TAG, "Opened ${model.file().name} with the OMNI fallback architecture") }
    }

    /**
     * One set of task options. The sample rate is the feed's constant 16 kHz because the session is
     * created once and keeps decoder state: it must never be asked to reinterpret a buffer at a
     * rate it was not opened with.
     */
    private fun options(architecture: AudioTranscriber.ModelArchitecture): AudioTranscriber.AudioTranscriberOptions = AudioTranscriber.AudioTranscriberOptions.builder()
        .setBaseOptions(
            BaseOptions.builder()
                .setModelAssetPath(model.file().absolutePath)
                .setDelegate(Delegate.CPU)
                .build(),
        )
        .setSampleRateHz(AudioFeed.TARGET_SAMPLE_RATE)
        .setEnableInterimResults(false)
        .setModelArchitecture(architecture)
        .setCacheDir(model.cacheDirectory().absolutePath)
        .build()

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

    private fun modelMissingMessage(): String = "the parakeet speech model is not installed at ${model.file().absolutePath} and was not downloaded"

    private companion object {
        /** How often the partial cue list is published while a pass runs. */
        const val PUBLISH_INTERVAL_MS = 2_000L

        /** How often a progress line is written while a pass runs. */
        const val PROGRESS_LOG_INTERVAL_MS = 5_000L

        /** How much of a segment's text the per-segment debug line carries. */
        const val SEGMENT_LOG_CHARS = 96

        /**
         * How long one pass may take, whatever the source. Parakeet measured 2x real time on a short
         * cold-bundle clip and should do better on a full episode, so this is not a working limit: it
         * only keeps a wedged pass from holding the worker forever.
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
 * The language allowlist, as a pure function of the podcast's declared language.
 *
 * `Podcast.podcastLanguage` is free-form server data (`"en"`, `"es-us"`, `"pt-BR"`, `""`), while
 * Parakeet v3 was trained on a fixed set of European languages. Reducing to the primary subtag is
 * what makes `"es-us"` and `"pt_BR"` match their language.
 *
 * A show that declares nothing gets English: plenty of feeds omit `<language>` entirely, and
 * skipping those would leave their episodes without a transcript for no reason. A show that does
 * declare a language the model was not trained on (Japanese, Chinese, ...) is skipped instead —
 * running the model over audio it does not know would produce confident nonsense, which is worse
 * than leaving the episode without on-device detection.
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
