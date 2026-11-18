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
 * The Parakeet files this app runs, and where they live.
 *
 * One model covers every supported language: Parakeet TDT 0.6B v3 is multilingual, so unlike the
 * per-language models it replaces there is no language allowlist or per-language cache key to consult
 * before checking for it. It is shipped as two graphs, because one graph cannot serve all three
 * accelerators:
 *
 * - `parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite`, 614 261 072 bytes: the portable export the CPU and
 *   GPU run on any device.
 * - `parakeet_tdt_0.6b_v3_5s_f32_stateful_Google_Tensor_G6.tflite`, 1 303 768 096 bytes: AOT-compiled
 *   for the Pixel 11's NPU, and only runnable there — its compiled ops are a `DISPATCH_OP` the CPU
 *   and GPU accelerators do not have.
 *
 * Neither fits in an APK, so a private build pushes them onto the device
 * (`dev/adaware/next/parakeet/install-model.ps1`) and a download is the fallback for when they are
 * missing. Both paths land in `noBackupFilesDir/parakeet-model/`.
 *
 * The tokenizer is the third file. MediaPipe used to run the bundle's built-in tokenizer; a
 * `.tflite` graph stops at token ids, so the `tokenizer.json` the model was converted with has to sit
 * beside it. No file needs a cache directory: LiteRT compiles and caches nothing for an AOT-compiled
 * graph, so a half-written artifact can never be mistaken for a missing model.
 */
@Singleton
class ParakeetModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @Downloads private val httpClient: Lazy<OkHttpClient>,
) {
    /** Absolute path of the graph [backend] runs, whether or not a byte of it is on disk. */
    fun file(backend: ParakeetBackend = ParakeetBackend.preferred()): File = File(modelDirectory(), graphOf(backend).name)

    /** Absolute path of the tokenizer, whether or not it is on disk. Never creates anything. */
    fun tokenizerFile(): File = File(modelDirectory(), TOKENIZER.name)

    /**
     * The directory both files sit in, created on demand. A download and an `adb push` both write
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
     * True when both files are on disk and whole. Never touches the network, and it is the first
     * thing [ensureDownloaded] asks, so a private build with the files already pushed never opens a
     * connection.
     *
     * The length is part of the check rather than a plain existence check: a copy killed mid-write
     * leaves a short file, and trusting it makes LiteRT fail inside its native loader where nothing
     * points back at the download. Any other size is treated as absent, so the next pass fetches the
     * file again instead of failing every episode forever.
     */
    fun isPresent(backend: ParakeetBackend = ParakeetBackend.preferred()): Boolean = assets(backend).all { fileOf(it).isPresent(it.bytes) }

    /**
     * True when the active network may be used for the 1.2 GiB download. Not a condition for
     * transcription: it only decides whether missing files are fetched now or once Wi-Fi returns.
     */
    fun canDownload(): Boolean = !isActiveNetworkMetered(context)

    /**
     * Makes sure every file is on disk, downloading only what is missing.
     *
     * Blocking network IO, so it runs on [Dispatchers.IO]. A killed download leaves `<name>.part`
     * and the next call resumes it with a `Range` request, which matters at this size: a dropped
     * connection near the end would otherwise throw away over a gigabyte. The metered check happens
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
         * The portable graph: the same weights in a form the CPU and GPU can run on any device.
         * This is what a phone without a Google Tensor NPU uses, and what the GPU measurement runs.
         */
        internal val GRAPH = ParakeetAsset(
            name = "parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite",
            url = "https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3/resolve/main/" +
                "parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite",
            bytes = 614_261_072L,
        )

        /**
         * The graph compiled for the Google Tensor G6 NPU, and only runnable on its NPU: the compiled
         * ops appear as a `DISPATCH_OP` a CPU or GPU accelerator cannot execute, which is why the
         * portable graph above exists rather than one graph serving all three backends.
         */
        internal val NPU_GRAPH = ParakeetAsset(
            name = "parakeet_tdt_0.6b_v3_5s_f32_stateful_Google_Tensor_G6.tflite",
            url = "https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3/resolve/main/" +
                "parakeet_tdt_0.6b_v3_5s_f32_stateful_Google_Tensor_G6.tflite",
            bytes = 1_303_768_096L,
        )

        internal val TOKENIZER = ParakeetAsset(
            name = "tokenizer.json",
            url = "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/resolve/main/tokenizer.json",
            bytes = 1_159_960L,
        )

        /** The graph a [backend] runs; the NPU is the only one that needs its own compilation. */
        internal fun graphOf(backend: ParakeetBackend): ParakeetAsset = if (backend == ParakeetBackend.NPU) NPU_GRAPH else GRAPH

        /** The graph's file name, kept named for the installer scripts and the device test. */
        val FILE_NAME: String get() = GRAPH.name

        /** The graph's size, which is the presence check a truncated push has to fail. */
        val EXPECTED_BYTES: Long get() = GRAPH.bytes

        val URL: String get() = GRAPH.url

        /** Every file a pass on [backend] needs, in the order they are downloaded. */
        internal fun assets(backend: ParakeetBackend): List<ParakeetAsset> = listOf(graphOf(backend), TOKENIZER)

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
         * Engine + model + cue-rule identity, stamped on every analysis. Both graphs are named
         * because either can produce a transcript on a given phone; changing either, the runtime or
         * the cue rules invalidates every stored transcript, which is the point.
         */
        val version: String =
            "$ENGINE_TAG-tdt-0.6b-v3/i8+g6-stateful/tflite-$RUNTIME_VERSION/$CUES_REVISION"
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
