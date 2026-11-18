package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.ModelDownload
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.ModelDownloadProgress
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert.ParakeetBackend
import au.com.shiftyjelly.pocketcasts.servers.di.Downloads
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Parakeet Redux files this app runs, and where they live.
 *
 * One model covers every supported language: Parakeet TDT 0.6B v3 is multilingual, so there is no
 * language allowlist or per-language model to pick before checking for it. Redux is the same weights
 * with a ternary encoder, exported by `litert-community/parakeet-redux` as four files because the
 * conversion splits what the old bundle kept together:
 *
 * - `parakeet_redux_mel_10s.tflite`, 1 608 068 bytes: the log-mel front end for 10 s of 16 kHz
 *   audio. It replaces the Kotlin mel implementation and is the only one that matches the weights.
 * - `parakeet_redux_encoder_10s_i4.tflite`, 345 895 584 bytes: the FastConformer at 4-bit weights —
 *   the CPU encoder, and the default on every device.
 * - `parakeet_redux_decoder_i8.tflite`, 17 866 768 bytes: the TDT prediction and joint networks, run
 *   on the CPU by every backend.
 * - `tokenizer.json`, 1 159 960 bytes: a `.tflite` graph stops at token ids, so the BPE table has to
 *   sit beside it.
 *
 * Optionally `parakeet_redux_encoder_10s_f16.tflite`, 1 194 680 560 bytes, is the float16 encoder the
 * GPU and NPU can use. It is not part of a normal install: only a pass pinned to [ParakeetBackend.GPU]
 * or [ParakeetBackend.NPU] asks for it (see [assets]).
 *
 * None fits in an APK, so a private build pushes them onto the device
 * (`dev/adaware/next/parakeet/install-model.ps1`) and a download is the fallback for when they are
 * missing. Both paths land in `noBackupFilesDir/parakeet-model/`. No file needs a cache directory:
 * LiteRT compiles and caches nothing for these graphs, so a half-written artifact can never be
 * mistaken for a missing model.
 */
@Singleton
class ParakeetModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @Downloads private val httpClient: Lazy<OkHttpClient>,
) {
    /** Absolute path of the mel front end, whether or not a byte of it is on disk. */
    fun melFile(): File = File(modelDirectory(), MEL.name)

    /** Absolute path of the encoder [backend] runs, whether or not a byte of it is on disk. */
    fun encoderFile(backend: ParakeetBackend = ParakeetBackend.preferred()): File = File(modelDirectory(), encoderOf(backend).name)

    /** Absolute path of the prediction and joint networks. The same file on every backend. */
    fun decoderFile(): File = File(modelDirectory(), DECODER.name)

    /** Absolute path of the tokenizer, whether or not it is on disk. Never creates anything. */
    fun tokenizerFile(): File = File(modelDirectory(), TOKENIZER.name)

    /**
     * The directory every file sits in, created on demand. A download and an `adb push` both write
     * here, so it has to exist before either runs.
     */
    fun directory(): File = modelDirectory().apply { mkdirs() }

    private fun modelDirectory(): File = File(context.noBackupFilesDir, MODEL_DIRECTORY)

    /** Where [asset] lives once downloaded or pushed. */
    private fun fileOf(asset: ParakeetAsset): File = File(modelDirectory(), asset.name)

    /**
     * The in-progress download of [asset]. It sits beside the finished file and is never read as it,
     * so a killed download can leave megabytes of a previous attempt without breaking the next pass.
     */
    internal fun partFile(asset: ParakeetAsset): File = File(modelDirectory(), "${asset.name}.part")

    /**
     * True when every file [backend] needs is on disk and whole. Never touches the network, and it
     * is the first thing [ensureDownloaded] asks, so a private build with the files already pushed
     * never opens a connection.
     *
     * The length is part of the check rather than a plain existence check: a copy killed mid-write
     * leaves a short file, and trusting it makes LiteRT fail inside its native loader where nothing
     * points back at the download. Any other size is treated as absent, so the next pass fetches the
     * file again instead of failing every episode forever.
     */
    fun isPresent(backend: ParakeetBackend = ParakeetBackend.preferred()): Boolean = assets(backend).all { fileOf(it).isPresent(it.bytes) }

    /**
     * True when the active network may be used for the 366 MB download. Not a condition for
     * transcription: it only decides whether missing files are fetched now or once Wi-Fi returns.
     */
    fun canDownload(): Boolean = !isActiveNetworkMetered(context)

    /**
     * Makes sure every file is on disk, downloading only what is missing.
     *
     * Blocking network IO, so it runs on [Dispatchers.IO]. A killed download leaves `<name>.part`
     * and the next call resumes it with a `Range` request, which matters at this size: a dropped
     * connection near the end would otherwise throw away most of a file. The metered check happens
     * before the first request, so a phone on cellular reports
     * [ModelDownload.NeedsUnmeteredNetwork] instead of spending someone's data allowance.
     */
    suspend fun ensureDownloaded(
        backend: ParakeetBackend = ParakeetBackend.preferred(),
        onProgress: (ModelDownloadProgress) -> Unit = {},
    ): ModelDownload = withContext(Dispatchers.IO) {
        if (isPresent(backend)) return@withContext ModelDownload.Present
        if (!canDownload()) {
            LogBuffer.w(
                TAG,
                "Parakeet files missing at ${directory().absolutePath} and the network is metered, " +
                    "waiting for Wi-Fi or an install push",
            )
            return@withContext ModelDownload.NeedsUnmeteredNetwork
        }
        val startedAt = SystemClock.elapsedRealtime()
        val missing = assets(backend).filterNot { fileOf(it).isPresent(it.bytes) }
        val totalBytes = missing.sumOf { it.bytes }
        var downloadedBytes = 0L
        for (asset in missing) {
            val part = partFile(asset)
            // A previous pass can have streamed every byte and been killed before the rename.
            // Promoting that file is cheaper and safer than a resume from its end, which would earn
            // an HTTP 416.
            if (part.isPresent(asset.bytes)) {
                promote(part, fileOf(asset))
                downloadedBytes += asset.bytes
                onProgress(ModelDownloadProgress(downloadedBytes, totalBytes))
                continue
            }
            download(
                asset = asset,
                part = part,
                alreadyDone = downloadedBytes,
                totalBytes = totalBytes,
                onProgress = onProgress,
            )
            downloadedBytes += asset.bytes
        }
        val wallMs = SystemClock.elapsedRealtime() - startedAt
        LogBuffer.i(
            TAG,
            "Parakeet files ready: $downloadedBytes bytes in ${wallMs}ms " +
                "(${megabytesPerSecond(downloadedBytes, wallMs)} MB/s)",
        )
        ModelDownload.Downloaded(downloadedBytes, wallMs)
    }

    /**
     * Streams [asset] into [part], appending to whatever a previous attempt left, then verifies the
     * size before moving it into place.
     *
     * No retries: the caller is a worker that retries the whole pass on its own schedule, and the
     * `.part` file is what makes that retry cheap. An HTTP error deletes the part, because a resume
     * from the same offset would only fail the same way.
     */
    private fun download(
        asset: ParakeetAsset,
        part: File,
        alreadyDone: Long,
        totalBytes: Long,
        onProgress: (ModelDownloadProgress) -> Unit,
    ) {
        val parentDirectory = part.parentFile
        if (parentDirectory != null && !parentDirectory.isDirectory && !parentDirectory.mkdirs()) {
            throw IOException("could not create ${parentDirectory.absolutePath} for the parakeet model")
        }
        var resumeAt = if (part.isFile) part.length() else 0L
        if (resumeAt >= asset.bytes) {
            // Longer than the file is corruption no resume can fix; a part of exactly the right
            // length is promoted by the caller, so this is only the oversized case.
            part.delete()
            resumeAt = 0L
        }
        LogBuffer.i(TAG, "Downloading ${asset.url} to ${fileOf(asset).absolutePath} (resuming at $resumeAt bytes)")
        val request = Request.Builder().url(asset.url).get()
        if (resumeAt > 0L) request.header("Range", "bytes=$resumeAt-")
        httpClient.get().newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) {
                part.delete()
                throw IOException("the parakeet download of ${asset.name} returned HTTP ${response.code}")
            }
            // A server that ignores `Range` answers 200 with the whole file; appending that to the
            // part would store a second copy after the first.
            val append = resumeAt > 0L && response.code == HTTP_PARTIAL_CONTENT
            if (!append) part.delete()
            val body = response.body
            val startBytes = if (append) resumeAt else 0L
            val contentBytes = body.contentLength().takeIf { it > 0L }
            val assetTotal = contentBytes?.let { startBytes + it } ?: asset.bytes
            var done = startBytes
            onProgress(ModelDownloadProgress(alreadyDone + done, totalBytes))
            var nextProgressAt = done + PROGRESS_EVERY_BYTES
            var nextLogAt = done + LOG_EVERY_BYTES
            FileOutputStream(part, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        done += read
                        // A chunk here is 64 KiB, a tenth of a percent of the progress bar, and
                        // every callback becomes a notification update in the worker; a megabyte is
                        // still smooth and removes thousands of updates over a file this size.
                        if (done >= nextProgressAt) {
                            onProgress(ModelDownloadProgress(alreadyDone + done, totalBytes))
                            nextProgressAt = done + PROGRESS_EVERY_BYTES
                        }
                        if (done >= nextLogAt) {
                            LogBuffer.i(
                                TAG,
                                "Parakeet download of ${asset.name}: $done/$assetTotal bytes " +
                                    "(${ParakeetStats.percent(done, assetTotal)}%)",
                            )
                            nextLogAt = done + LOG_EVERY_BYTES
                        }
                    }
                }
            }
            onProgress(ModelDownloadProgress(alreadyDone + done, totalBytes))
        }
        val size = part.length()
        if (size != asset.bytes) {
            // A short body cannot be told apart from a server that serves fewer bytes, so the part
            // is dropped rather than resumed: the next pass starts over and either succeeds whole
            // or fails with a number that says which side is wrong.
            part.delete()
            throw IOException("${asset.name} is $size bytes but the file is ${asset.bytes} bytes")
        }
        promote(part, fileOf(asset))
    }

    /**
     * Moves a complete part into place. A rename inside one directory is atomic on every Android
     * filesystem, so a file is never visible under its real name while it is still being written.
     */
    private fun promote(part: File, target: File) {
        target.delete()
        if (!part.renameTo(target)) {
            // The bytes are still good, so the next pass promotes them rather than downloading them
            // again; failing here is better than serving a half-named file to LiteRT.
            throw IOException("could not move ${part.absolutePath} to ${target.absolutePath}")
        }
    }

    companion object {
        /**
         * The log-mel front end for one 10 s window. Small enough to be an afterthought in time and
         * size, but the only implementation whose features the Redux encoder was trained on.
         */
        internal val MEL = ParakeetAsset(
            name = "parakeet_redux_mel_10s.tflite",
            url = "https://huggingface.co/litert-community/parakeet-redux/resolve/main/" +
                "parakeet_redux_mel_10s.tflite",
            bytes = 1_608_068L,
        )

        /**
         * The CPU encoder: the same FastConformer weights as the float16 export, ternary in the
         * linear layers and packed at 4 bits, which is what makes it a third of the size and the
         * fastest whole pass measured.
         */
        internal val ENCODER_I4 = ParakeetAsset(
            name = "parakeet_redux_encoder_10s_i4.tflite",
            url = "https://huggingface.co/litert-community/parakeet-redux/resolve/main/" +
                "parakeet_redux_encoder_10s_i4.tflite",
            bytes = 345_895_584L,
        )

        /**
         * The float16 encoder the GPU and NPU can take. Not part of a normal install: only a pass
         * pinned to one of those backends asks for it, because the CPU encoder is faster end to end
         * than the decoder's per-token dispatch on an accelerator.
         */
        internal val ENCODER_F16 = ParakeetAsset(
            name = "parakeet_redux_encoder_10s_f16.tflite",
            url = "https://huggingface.co/litert-community/parakeet-redux/resolve/main/" +
                "parakeet_redux_encoder_10s_f16.tflite",
            bytes = 1_194_680_560L,
        )

        /**
         * The TDT prediction (`predict`) and joint (`joint`) networks. Tiny next to the encoder and
         * strictly sequential, so every backend runs it on the CPU.
         */
        internal val DECODER = ParakeetAsset(
            name = "parakeet_redux_decoder_i8.tflite",
            url = "https://huggingface.co/litert-community/parakeet-redux/resolve/main/" +
                "parakeet_redux_decoder_i8.tflite",
            bytes = 17_866_768L,
        )

        internal val TOKENIZER = ParakeetAsset(
            name = "tokenizer.json",
            url = "https://huggingface.co/litert-community/parakeet-redux/resolve/main/tokenizer.json",
            bytes = 1_159_960L,
        )

        /** The encoder a [backend] runs; only the CPU has its own export. */
        internal fun encoderOf(backend: ParakeetBackend): ParakeetAsset = if (backend == ParakeetBackend.CPU) ENCODER_I4 else ENCODER_F16

        /** The CPU encoder's file name, the one a normal install pushes. */
        val ENCODER_NAME: String get() = ENCODER_I4.name

        /** Its size, which is the presence check a truncated push has to fail. */
        val ENCODER_BYTES: Long get() = ENCODER_I4.bytes

        val ENCODER_URL: String get() = ENCODER_I4.url

        /** Every file a pass on [backend] needs, smallest first so an interrupted fetch is useful. */
        internal fun assets(backend: ParakeetBackend): List<ParakeetAsset> = listOf(TOKENIZER, MEL, DECODER, encoderOf(backend))

        /** Prefix of the `sttVersion` stamped on analyses; OnDeviceTranscriptGenerator matches on it. */
        const val ENGINE_TAG = "parakeet"

        /** The LiteRT runtime the graphs are run with. */
        const val RUNTIME_VERSION = "2.3.2"

        /**
         * Revision of the cue rules in [ParakeetCues]. Bump it whenever the same words would be
         * turned into different cues, so stored transcripts are rebuilt instead of being reused.
         */
        const val CUES_REVISION = "cues1"

        /**
         * Engine + model + cue-rule identity, stamped on every analysis. Changing any of the four
         * files, the runtime or the cue rules invalidates every stored transcript, which is the
         * point: Redux does not produce the same words as the export it replaces.
         */
        val version: String =
            "$ENGINE_TAG-tdt-0.6b-v3-redux/i4/tflite-$RUNTIME_VERSION/$CUES_REVISION"
    }
}

/** One file a pass cannot run without. */
internal class ParakeetAsset(val name: String, val url: String, val bytes: Long)

/**
 * True when this file is the whole asset; anything else is treated as absent so the next pass
 * downloads it again.
 */
private fun File.isPresent(bytes: Long): Boolean = isFile && length() == bytes

/** Decimal megabytes per second, so the logged speed can be compared with a download screen. */
private fun megabytesPerSecond(bytes: Long, wallMs: Long): String = when {
    wallMs <= 0L -> "0.00"
    else -> String.format(Locale.US, "%.2f", bytes / 1_000_000.0 / (wallMs / 1000.0))
}

/**
 * Whether the active network is metered. A missing answer (no network, or no service object) counts
 * as metered, because the caller only asks when it is about to move a gigabyte.
 */
private fun isActiveNetworkMetered(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    return try {
        manager.isActiveNetworkMetered
    } catch (e: SecurityException) {
        LogBuffer.e(TAG, e, "Could not read the network's metered state")
        true
    }
}

private const val TAG = ParakeetStats.TAG
private const val MODEL_DIRECTORY = "parakeet-model"
private const val HTTP_PARTIAL_CONTENT = 206
private const val BUFFER_BYTES = 64 * 1024
private const val PROGRESS_EVERY_BYTES = 1024 * 1024L
private const val LOG_EVERY_BYTES = 16 * 1024 * 1024L
