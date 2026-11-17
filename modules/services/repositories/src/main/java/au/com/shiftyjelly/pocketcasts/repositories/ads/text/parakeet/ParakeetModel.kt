package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.ModelDownload
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.ModelDownloadProgress
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
 * The Parakeet bundle this app runs, and where it lives.
 *
 * One file covers every supported language: Parakeet TDT 0.6B v3 is multilingual, so unlike the
 * per-language models it replaces there is no language allowlist or per-language cache key to
 * consult before checking for it. The bundle is 586 MiB, which is why it is never bundled in the
 * APK — half a gigabyte of weights for a gated feature does not belong there. A private build pushes
 * it onto the device (`dev/adaware/next/parakeet/install-model.ps1`) and a download is the fallback
 * for when it is missing. Both paths land in `noBackupFilesDir/parakeet-model/<FILE_NAME>`, which is
 * where MediaPipe is pointed and where the installer pushes.
 *
 * MediaPipe derives a compiled artifact from the bundle on first use. It goes into
 * [cacheDirectory], not next to the bundle: it is disposable and may be larger than the model, and
 * clearing it must never look like a missing bundle that has to be downloaded again.
 */
@Singleton
class ParakeetModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @Downloads private val httpClient: Lazy<OkHttpClient>,
) {
    /** Absolute path of the bundle, whether or not a byte of it is on disk. Never creates anything. */
    fun file(): File = File(modelDirectory(), FILE_NAME)

    /**
     * The directory the bundle sits in, created on demand. A download and an `adb push` both write
     * here, so it has to exist before either runs. It is also safe to hand to MediaPipe as the
     * parent of its compiled artifact, though [cacheDirectory] is what the transcriber uses: keeping
     * the artifact out of this directory is what lets it be cleared on its own.
     */
    fun directory(): File = modelDirectory().apply { mkdirs() }

    /** MediaPipe's compiled-artifact cache, created on demand. Safe to delete at any time. */
    fun cacheDirectory(): File = File(context.noBackupFilesDir, CACHE_DIRECTORY).apply { mkdirs() }

    private fun modelDirectory(): File = File(context.noBackupFilesDir, MODEL_DIRECTORY)

    /**
     * The in-progress download. It sits beside the bundle and is never read as the bundle, so a
     * killed download can leave megabytes of a previous attempt without breaking the next pass.
     */
    internal fun partFile(): File = File(modelDirectory(), "$FILE_NAME.part")

    /**
     * True when the bundle is on disk and whole. Never touches the network, and it is the first
     * thing [ensureDownloaded] asks, so a private build with the bundle already pushed never opens a
     * connection.
     *
     * The length is part of the check rather than a plain existence check: a copy killed mid-write
     * leaves a short file, and trusting it makes MediaPipe fail inside its native loader where
     * nothing points back at the download. Any other size is treated as absent, so the next pass
     * fetches the bundle again instead of failing every episode forever.
     */
    fun isPresent(): Boolean = file().isPresentAsBundle()

    /**
     * True when the active network may be used for the 586 MiB download. Not a condition for
     * transcription: it only decides whether a missing bundle is fetched now or once Wi-Fi returns.
     */
    fun canDownload(): Boolean = !isActiveNetworkMetered(context)

    /**
     * Makes sure the bundle is on disk, downloading only what is missing.
     *
     * Blocking network IO, so it runs on [Dispatchers.IO]. A killed download leaves `<FILE_NAME>.part`
     * and the next call resumes it with a `Range` request, which matters at this size: a dropped
     * connection near the end would otherwise throw away almost six hundred megabytes. The metered
     * check happens before the first request, so a phone on cellular reports
     * [ModelDownload.NeedsUnmeteredNetwork] instead of spending someone's data allowance.
     */
    suspend fun ensureDownloaded(
        onProgress: (ModelDownloadProgress) -> Unit = {},
    ): ModelDownload = withContext(Dispatchers.IO) {
        if (isPresent()) return@withContext ModelDownload.Present
        if (!canDownload()) {
            LogBuffer.w(
                TAG,
                "Parakeet model missing at ${file().absolutePath} and the network is metered, " +
                    "waiting for Wi-Fi or an install push",
            )
            return@withContext ModelDownload.NeedsUnmeteredNetwork
        }
        val target = file()
        val part = partFile()
        val startedAt = SystemClock.elapsedRealtime()
        // A previous pass can have streamed every byte and been killed before the rename. Promoting
        // that file is cheaper and safer than a resume from its end, which would earn an HTTP 416.
        if (part.isPresentAsBundle()) {
            promote(part, target)
            val wallMs = SystemClock.elapsedRealtime() - startedAt
            LogBuffer.i(TAG, "Parakeet model moved into place: ${target.length()} bytes in ${wallMs}ms")
            return@withContext ModelDownload.Downloaded(EXPECTED_BYTES, wallMs)
        }
        download(target, part, onProgress)
        val wallMs = SystemClock.elapsedRealtime() - startedAt
        val bytes = target.length()
        LogBuffer.i(
            TAG,
            "Parakeet model ready: $bytes bytes in ${wallMs}ms (${megabytesPerSecond(bytes, wallMs)} MB/s)",
        )
        ModelDownload.Downloaded(bytes, wallMs)
    }

    /**
     * Streams the bundle into [part], appending to whatever a previous attempt left, then verifies
     * the size before moving it to [target].
     *
     * No retries: the caller is a worker that retries the whole pass on its own schedule, and the
     * `.part` file is what makes that retry cheap. An HTTP error deletes the part, because a resume
     * from the same offset would only fail the same way.
     */
    private fun download(target: File, part: File, onProgress: (ModelDownloadProgress) -> Unit) {
        val parentDirectory = part.parentFile
        if (parentDirectory != null && !parentDirectory.isDirectory && !parentDirectory.mkdirs()) {
            throw IOException("could not create ${parentDirectory.absolutePath} for the parakeet model")
        }
        var resumeAt = if (part.isFile) part.length() else 0L
        if (resumeAt >= EXPECTED_BYTES) {
            // Longer than the bundle is corruption no resume can fix; a part of exactly the bundle's
            // length is promoted by the caller, so this is only the oversized case.
            part.delete()
            resumeAt = 0L
        }
        LogBuffer.i(TAG, "Downloading $URL to ${target.absolutePath} (resuming at $resumeAt bytes)")
        val request = Request.Builder().url(URL).get()
        if (resumeAt > 0L) request.header("Range", "bytes=$resumeAt-")
        httpClient.get().newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) {
                part.delete()
                throw IOException("the parakeet model download returned HTTP ${response.code}")
            }
            // A server that ignores `Range` answers 200 with the whole file; appending that to the
            // part would store a second copy of the bundle after the first.
            val append = resumeAt > 0L && response.code == HTTP_PARTIAL_CONTENT
            if (!append) part.delete()
            val body = response.body
            val startBytes = if (append) resumeAt else 0L
            val contentBytes = body.contentLength().takeIf { it > 0L }
            val totalBytes = contentBytes?.let { startBytes + it } ?: EXPECTED_BYTES
            var done = startBytes
            onProgress(ModelDownloadProgress(done, totalBytes))
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
                        // A chunk here is 64 KiB, a tenth of a percent of the progress bar, and every
                        // callback becomes a notification update in the worker; a megabyte is still
                        // smooth and removes thousands of updates over a bundle this size.
                        if (done >= nextProgressAt) {
                            onProgress(ModelDownloadProgress(done, totalBytes))
                            nextProgressAt = done + PROGRESS_EVERY_BYTES
                        }
                        if (done >= nextLogAt) {
                            LogBuffer.i(
                                TAG,
                                "Parakeet model download: $done/$totalBytes bytes " +
                                    "(${ParakeetStats.percent(done, totalBytes)}%)",
                            )
                            nextLogAt = done + LOG_EVERY_BYTES
                        }
                    }
                }
            }
            onProgress(ModelDownloadProgress(done, totalBytes))
        }
        val size = part.length()
        if (size != EXPECTED_BYTES) {
            // A short body cannot be told apart from a server that serves fewer bytes, so the part
            // is dropped rather than resumed: the next pass starts over and either succeeds whole
            // or fails with a number that says which side is wrong.
            part.delete()
            throw IOException("the parakeet model is $size bytes but $FILE_NAME is $EXPECTED_BYTES bytes")
        }
        promote(part, target)
    }

    /**
     * Moves a complete part into place. A rename inside one directory is atomic on every Android
     * filesystem, so a bundle is never visible at [FILE_NAME] while it is still being written.
     */
    private fun promote(part: File, target: File) {
        target.delete()
        if (!part.renameTo(target)) {
            // The bytes are still good, so the next pass promotes them rather than downloading them
            // again; failing here is better than serving a half-named bundle to MediaPipe.
            throw IOException("could not move ${part.absolutePath} to ${target.absolutePath}")
        }
    }

    companion object {
        const val FILE_NAME = "parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm"
        const val URL =
            "https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3/resolve/main/" +
                "parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm"
        const val EXPECTED_BYTES = 614_588_752L

        /** Prefix of the `sttVersion` stamped on analyses; OnDeviceTranscriptGenerator matches on it. */
        const val ENGINE_TAG = "parakeet"

        /** The MediaPipe `tasks-audio` version the bundle is run with. */
        const val RUNTIME_VERSION = "1.2.3"

        /**
         * Revision of the cue rules in [ParakeetCues]. Bump it whenever the same segments would be
         * turned into different cues, so stored transcripts are rebuilt instead of being reused.
         */
        const val CUES_REVISION = "cues1"

        /**
         * Engine + model + cue-rule identity, stamped on every analysis. Derived from the parts
         * rather than written out again, so a runtime or cue-rule bump cannot leave a stale string
         * behind; changing any of them is meant to invalidate the whole library's transcripts.
         */
        val version: String =
            "$ENGINE_TAG-tdt-0.6b-v3/i8-5s-stateful/litertlm-$RUNTIME_VERSION/$CUES_REVISION"
    }
}

/**
 * True when this file holds a whole bundle; the model is valid only at exactly
 * [ParakeetModel.EXPECTED_BYTES].
 */
private fun File.isPresentAsBundle(): Boolean = isFile && length() == ParakeetModel.EXPECTED_BYTES

/** Decimal megabytes per second, so the logged speed can be compared with a download screen. */
private fun megabytesPerSecond(bytes: Long, wallMs: Long): String = when {
    wallMs <= 0L -> "0.00"
    else -> String.format(Locale.US, "%.2f", bytes / 1_000_000.0 / (wallMs / 1000.0))
}

/**
 * Whether the active network is metered. A missing answer (no network, or no service object) counts
 * as metered, because the caller only asks when it is about to move six hundred megabytes.
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
private const val CACHE_DIRECTORY = "parakeet-cache"
private const val HTTP_PARTIAL_CONTENT = 206
private const val BUFFER_BYTES = 64 * 1024
private const val PROGRESS_EVERY_BYTES = 1024 * 1024L
private const val LOG_EVERY_BYTES = 16 * 1024 * 1024L
