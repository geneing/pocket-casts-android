package au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote

import android.content.Context
import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.EpisodeTranscriber
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.ModelDownloadProgress
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.SpeechLanguage
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
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call

/**
 * Transcribes an episode with a remote speech-to-text model over OpenRouter.
 *
 * The model is not reachable as a stream and its provider refuses a URL, so the pass uploads the
 * audio itself: the episode is decoded to 16 kHz mono, encoded as 32 kbit/s AAC in an m4a, and sent
 * one [CHUNK_MS] at a time. Ten minutes per request costs about 24 s of recognition and 2.4 MB of
 * upload, which is well inside the endpoint's 50 MiB body limit; a whole hour of audio is therefore
 * roughly six requests and 15 MB.
 *
 * Chunks overlap by [OVERLAP_MS] on each side. A hard cut costs about two seconds of speech (the
 * measured seam is in `dev/adaware/remote-asr/README.md`), so each request hears a little of its
 * neighbour and [RemoteCues] keeps only the cues that start inside the chunk's own window.
 *
 * Cue timestamps come from the model's own word times; nothing is estimated. The cost the provider
 * reports is summed across the pass and travels back in [TranscriptResult.Success] so the caller can
 * show it at the end of the transcript.
 */
@Singleton
class RemoteEpisodeTranscriber @Inject constructor(
    private val cache: RemoteAudioCache,
    private val client: RemoteSttClient,
    /** The client the download worker fetches with, so a streamed episode is the copy it plays. */
    @Downloads private val downloads: Lazy<Call.Factory>,
) : EpisodeTranscriber {

    /**
     * The analysis identity: the engine, the model, the chunking rule and the encoding. A change to
     * any of them moves the words or the times, so every stored transcript made with this string
     * stops matching and is recognised again.
     */
    override val version: String = buildString {
        append(ENGINE_TAG)
        append('/')
        append(RemoteSttClient.MODEL)
        append('/')
        append(CHUNK_MS / 1_000)
        append("s/")
        // Chunks are copies of the episode's own frames rather than AAC the app encoded, so the
        // audio a stored cue was heard in differs from every earlier version's.
        append("copy")
        // Bumped by hand when the chunking, the overlap or the encoding changes in a way that moves
        // a word or a time; the whole point of the version is that old cues stop matching.
        append("/v2")
    }

    override fun isAvailable(): Boolean = client.isConfigured()

    override fun availability(podcastLanguage: String?): TranscriptAvailability {
        if (!isAvailable()) return TranscriptAvailability.EngineUnavailable
        if (SpeechLanguage.primary(podcastLanguage) == null) return TranscriptAvailability.UnsupportedLanguage
        return TranscriptAvailability.Ready
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
            return TranscriptResult.Unavailable("hls stream")
        }
        if (!isAvailable()) {
            return TranscriptResult.Unavailable("no OpenRouter key configured for remote transcription")
        }
        val language = SpeechLanguage.primary(request.podcastLanguage) ?: return TranscriptResult.Unavailable(
            "no remote model for podcast language '${request.podcastLanguage}'",
        )

        return withContext(Dispatchers.IO) {
            // A pass that was killed owns nothing here any more, and neither does a file old enough
            // that no pass could still be reading it: both are removed before any bytes are spent.
            cache.deleteFor(request.episodeUuid)
            cache.deleteStale()
            try {
                val local = when (source) {
                    is TranscriptSource.Downloaded -> source.file

                    is TranscriptSource.Stream -> fetchStream(request.episodeUuid, source.url)
                        ?: return@withContext TranscriptResult.Failed("could not download the episode audio")
                }
                transcribeAudio(request, language, local, onPartialCues)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBuffer.e(TAG, e, "Could not transcribe ${request.episodeUuid} remotely")
                TranscriptResult.Failed("remote transcription failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                // The stream copy and the last chunk, whether the pass finished, failed or was
                // cancelled. A process kill is the one case this cannot cover, and the sweep at the
                // start of the next pass plus the retention worker handle that one.
                cache.deleteFor(request.episodeUuid)
            }
        }
    }

    private suspend fun transcribeAudio(
        request: TranscriptRequest,
        language: String,
        file: File,
        onPartialCues: suspend (List<TranscriptCue>) -> Unit,
    ): TranscriptResult {
        val job = currentCoroutineContext()[Job]
        val isActive = { job?.isActive == true }
        val passStartedAt = SystemClock.elapsedRealtime()
        val durationMs = request.durationMs
        val chunk = cache.file(request.episodeUuid, AacChunks.FORMAT)
        val cues = ArrayList<TranscriptCue>()
        var cursor = request.startMs.coerceAtLeast(0L)
        var costUsd = 0.0
        var priceKnown = false
        var uploadedBytes = 0L
        var audioMs = 0L
        var requests = 0
        var encodeTotalMs = 0L
        var copyTotalMs = 0L
        var requestTotalMs = 0L

        /** Uploads one request's audio and files its cues; returns the failure to report, or null. */
        suspend fun upload(
            audio: ByteArray,
            format: String,
            originMs: Long,
            fromMs: Long,
            untilMs: Long,
            prepareMs: Long = 0L,
            copied: Boolean = false,
            /** The audio the chunk carried, which is where its cues may end. Zero when unknown. */
            chunkAudioMs: Long = 0L,
        ): TranscriptResult? {
            currentCoroutineContext().ensureActive()
            val startedAt = SystemClock.elapsedRealtime()
            val result = client.transcribe(audio, format, language)
            val requestMs = SystemClock.elapsedRealtime() - startedAt
            requestTotalMs += requestMs
            when (result) {
                RemoteSttResult.NoApiKey -> return TranscriptResult.Unavailable(
                    "no OpenRouter key configured for remote transcription",
                )

                is RemoteSttResult.Failed -> return TranscriptResult.Failed("remote transcription: ${result.message}")

                is RemoteSttResult.Success -> {
                    requests++
                    uploadedBytes += audio.size
                    audioMs += result.transcript.audioSeconds.takeIf { it > 0.0 }?.let { (it * 1_000).toLong() }
                        ?: (untilMs.takeIf { it != Long.MAX_VALUE }?.minus(fromMs) ?: 0L).coerceAtLeast(0L)
                    result.transcript.costUsd?.let { cost ->
                        costUsd += cost
                        priceKnown = true
                    }
                    val incoming = RemoteCues.toCues(
                        transcript = result.transcript,
                        originMs = originMs,
                        fromMs = fromMs,
                        untilMs = untilMs,
                        durationMs = durationMs,
                        audioMs = chunkAudioMs,
                        notBeforeMs = cues.lastOrNull()?.endMs ?: 0L,
                    )
                    if (incoming.isNotEmpty()) {
                        cues += incoming
                        onPartialCues(cues.toList())
                    }
                    LogBuffer.i(
                        TAG,
                        "chunk $requests: format=$format split=${if (copied) "yes" else "no"} prepare=${prepareMs}ms request=${requestMs}ms " +
                            "upload=${audio.size}B span=${if (untilMs == Long.MAX_VALUE) "all" else "${untilMs - fromMs}ms"} " +
                            "cues=${incoming.size} cost=${result.transcript.costUsd ?: "unreported"}",
                    )
                    return null
                }
            }
        }

        LogBuffer.i(
            TAG,
            "$language start: ${file.name} ${durationMs / 1000}s from ${cursor}ms on ${RemoteSttClient.MODEL}",
        )

        try {
            // A short episode is uploaded exactly as it was downloaded: the provider transcodes to
            // 16 kHz mono itself, so re-encoding would only add a generation of loss and CPU for
            // bytes the listener already paid to fetch. Chunking is for what does not fit.
            val original = originalUpload(file, cursor)
            if (original != null) {
                LogBuffer.i(TAG, "Uploading the original ${original.format} (${original.bytes.size} bytes) in one request")
                upload(original.bytes, original.format, originMs = 0L, fromMs = cursor, untilMs = Long.MAX_VALUE)?.let { return it }
            } else {
                // The episode's own audio is copied, not re-encoded: a run of whole frames is a
                // valid file by itself, so a chunk costs a byte copy and the pass stops being CPU
                // bound. Only a file that cannot be repacked (a WAV, or a codec the muxer cannot
                // write) is decoded and encoded, by [AacChunks], for every window of the episode.
                var splitter = AudioSplitter.open(file)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (durationMs > 0L && cursor >= durationMs) break
                    // Unknown duration: one chunk to the end of the file, which the decoder finds.
                    val windowEndMs = if (durationMs > 0L) minOf(cursor + CHUNK_MS, durationMs) else Long.MAX_VALUE
                    val fromMs = (cursor - OVERLAP_MS).coerceAtLeast(0L)
                    val untilMs = if (durationMs > 0L) (windowEndMs + OVERLAP_MS).coerceAtMost(durationMs) else Long.MAX_VALUE

                    val prepareStartedAt = SystemClock.elapsedRealtime()
                    var prepared: ChunkAudio? = splitter?.let { split ->
                        val target = cache.file(request.episodeUuid, "chunk.${split.extension}")
                        val attempt = runCatching { split.slice(fromMs, untilMs, MAX_ORIGINAL_BYTES, target) }
                        val slice = attempt.getOrNull()
                        if (slice == null && attempt.isFailure) {
                            // The platform refused this file after all (a codec the muxer cannot
                            // write, a damaged index): the rest of the episode is re-encoded.
                            LogBuffer.w(
                                TAG,
                                "Could not copy ${file.name} at ${fromMs}ms as ${split.format}: " +
                                    "${attempt.exceptionOrNull()?.message}; encoding instead",
                            )
                            splitter = null
                        }
                        slice
                    }
                    if (prepared == null && splitter == null) {
                        prepared = AacChunks.encode(file, fromMs, untilMs, chunk, isActive)
                            ?: return TranscriptResult.Failed("could not encode the audio at ${fromMs}ms as AAC")
                    }
                    // A splitter that found no audio past this point means the file ended before
                    // the duration the episode claims; there is nothing left to send.
                    if (prepared == null) break
                    if (prepared.audioMs <= 0L) {
                        runCatching { prepared.file.delete() }
                        break
                    }

                    // Read and delete in one step: from here the chunk exists only in memory, so a
                    // kill during the upload leaves nothing behind.
                    val audio = prepared.file.readBytes()
                    runCatching { prepared.file.delete() }
                    val prepareMs = SystemClock.elapsedRealtime() - prepareStartedAt
                    if (prepared.copied) copyTotalMs += prepareMs else encodeTotalMs += prepareMs
                    upload(
                        audio = audio,
                        format = prepared.format,
                        originMs = prepared.originMs,
                        fromMs = cursor,
                        untilMs = windowEndMs,
                        prepareMs = prepareMs,
                        copied = prepared.copied,
                        chunkAudioMs = prepared.audioMs,
                    )?.let { return it }

                    if (durationMs <= 0L) break
                    // A window can carry less than it asked for: the byte cap stops a slice at the
                    // last frame that fits, and a file can end inside a window. The next window then
                    // starts where this one actually ended, so no audio falls between two requests.
                    val coveredMs = prepared.originMs + prepared.audioMs
                    cursor = if (coveredMs > cursor && coveredMs < windowEndMs) coveredMs else windowEndMs
                }
            }
        } finally {
            runCatching { chunk.delete() }
        }

        val wallMs = SystemClock.elapsedRealtime() - passStartedAt
        LogBuffer.i(
            TAG,
            "pass: model=${RemoteSttClient.MODEL} audio=${audioMs}ms wall=${wallMs}ms " +
                "copy=${copyTotalMs}ms encode=${encodeTotalMs}ms requests=${requestTotalMs}ms " +
                "requests=$requests upload=${uploadedBytes}B cues=${cues.size} " +
                "cost=${if (priceKnown) formatUsd(costUsd) else "unreported"} language=$language",
        )
        if (cues.isEmpty()) return TranscriptResult.Failed("remote transcription returned no speech")
        return TranscriptResult.Success(cues, version, costUsd.takeIf { priceKnown })
    }

    /**
     * The episode's own file, when the endpoint can take it whole.
     *
     * The limit is the endpoint's 50 MiB request body, which carries base64, and the memory this
     * pass may hold: the base64 string, the JSON around it and the request body are three copies, so
     * 24 MiB of audio (~32 MiB of JSON) peaks near 100 MB instead of near 150. An episode under
     * about 25 minutes at 128 kbit/s takes this path; longer ones are chunked.
     *
     * Only compressed containers and a small WAV take it — an unknown extension is chunked through
     * [AacChunks], because uploading raw audio would cost eight times the bytes. A WAV under the
     * same limit is sent as it is: at that size it is only a couple of minutes, and re-encoding it
     * would add a generation of loss to a file that already fits. A resumed pass chunks as well:
     * the part already transcribed is not paid for a second time.
     */
    private fun originalUpload(file: File, startMs: Long): OriginalUpload? {
        if (startMs > 0L) return null
        val extension = file.extension.lowercase()
        val format = COMPRESSED_FORMATS[extension] ?: UNCOMPRESSED_FORMATS[extension] ?: return null
        if (file.length() > MAX_ORIGINAL_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return OriginalUpload(bytes, format)
    }

    private data class OriginalUpload(val bytes: ByteArray, val format: String)

    /**
     * Fetches a streamed episode into the cache so it can be decoded like a download, exactly as the
     * on-device pass does: the recogniser reads a file, and the file has to be the copy the listener
     * heard, because a stream and a download of one episode can carry different ad stitches.
     */
    private fun fetchStream(episodeUuid: String, url: String): File? {
        val target = cache.file(episodeUuid, url.cachedExtension())
        val startedAt = SystemClock.elapsedRealtime()
        val fetched = downloadAudioStream(downloads.get(), url, target)
        LogBuffer.i(
            TAG,
            if (fetched == null) {
                "Could not fetch the stream for $episodeUuid"
            } else {
                "Fetched ${fetched.length()} bytes of stream audio in ${SystemClock.elapsedRealtime() - startedAt}ms"
            },
        )
        return fetched
    }

    private fun formatUsd(usd: Double): String = String.format(Locale.US, "\$%.6f", usd)

    /** A playlist URL can never be decoded as audio, whatever the flag on the source says. */
    private fun String.isPlaylistUrl(): Boolean = substringBefore('?').lowercase().endsWith(".m3u8")

    /**
     * The extension the stream is cached under. It is kept from the URL so the cached copy can be
     * uploaded as it was fetched instead of being re-encoded, and the URL's query string is
     * dropped because a signed CDN link puts its parameters there.
     */
    private fun String.cachedExtension(): String = substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in COMPRESSED_FORMATS } ?: "audio"

    companion object {
        private const val TAG = "RemoteAsr"

        /** Prefix of the `sttVersion` stamped on analyses made by this engine. */
        const val ENGINE_TAG = "remote-nemotron"

        /** How much audio one request carries. Ten minutes is ~24 s of recognition and 2.4 MB. */
        const val CHUNK_MS = 10 * 60_000L

        /** Audio included on each side of a chunk so a sentence across a seam is heard whole. */
        const val OVERLAP_MS = 3_000L

        /**
         * The most original audio one request may carry. The endpoint refuses a body over 50 MiB
         * (`413 Request body of … exceeds the maximum allowed size of 52428800 bytes`) and the audio
         * travels base64; 24 MiB of audio is ~32 MiB of JSON, which also keeps the three in-memory
         * copies of it from a long episode's worth of audio on a small heap.
         */
        const val MAX_ORIGINAL_BYTES = 24L * 1024 * 1024

        /**
         * Containers the endpoint takes as they are, mapped to the `input_audio.format` it expects.
         * An extension outside this map and [UNCOMPRESSED_FORMATS] is re-encoded.
         */
        private val COMPRESSED_FORMATS = mapOf(
            "mp3" to "mp3",
            "m4a" to "m4a",
            "mp4" to "m4a",
            "aac" to "aac",
            "ogg" to "ogg",
            "oga" to "ogg",
            "opus" to "ogg",
            "webm" to "webm",
            "flac" to "flac",
        )

        /**
         * Raw audio the endpoint also takes, but only while it fits whole: a WAV is eight times the
         * bytes of the same speech compressed, so a long one is rewritten as AAC instead.
         */
        private val UNCOMPRESSED_FORMATS = mapOf(
            "wav" to "wav",
            "wave" to "wav",
        )
    }
}
