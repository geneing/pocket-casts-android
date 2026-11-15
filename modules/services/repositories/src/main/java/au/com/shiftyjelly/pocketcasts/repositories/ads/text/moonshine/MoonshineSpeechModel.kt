package au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine

import ai.moonshine.voice.AssetDownloader
import ai.moonshine.voice.JNI
import ai.moonshine.voice.ModelCache
import ai.moonshine.voice.ModelSpec
import android.content.Context
import android.net.ConnectivityManager
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.ModelDownloadProgress
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The speech models this app will run, and where they live.
 *
 * Only English and Spanish are offered. Every language is requested as **tiny streaming** with the
 * architecture passed explicitly: the catalog's default for English is *medium* and for Spanish
 * *small*, so letting the default decide would silently fetch hundreds of megabytes instead of the
 * 74.5 MiB (en, with the attention decoder that produces word timestamps) / 30.8 MiB (es) these
 * two need. Word timestamps are English only, because the Spanish bundle ships no attention
 * decoder; Spanish cues come from line times instead.
 *
 * Models are never bundled in the APK. A private build copies them onto the device
 * (`dev/adaware/next/moonshine/install-models.ps1`), and a download is the fallback for when they
 * are missing. Both paths land in the same place: `noBackupFilesDir/moonshine-models/<cache key>`,
 * which is exactly what [ModelCache] reads and what the installer pushes to.
 */
enum class MoonshineLanguage(val code: String, val wordTimestamps: Boolean) {
    EN("en", true),
    ES("es", false),
    ;

    /** Always tiny streaming. See the class comment: the catalog default is not this model. */
    val arch: Int get() = JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING

    fun spec(): ModelSpec = ModelSpec.stt(code, arch, false, wordTimestamps)

    /** The cache-key the installer has to reproduce: `stt-en-2-wt` for English, `stt-es-2` for Spanish. */
    val cacheKey: String get() = ModelCache.key(spec())

    /**
     * The identity of the model behind a transcript, including the timestamp policy. Per language
     * because the two bundles are different files; [MoonshineModels.engineVersion] is the single
     * string an analysis is pinned to.
     */
    val versionTag: String get() = "moonshine-${MoonshineModels.VERSION}/tiny-streaming-$code" + if (wordTimestamps) "/wt" else ""

    /** How this language appears in the engine-wide identity: `en-wt`, `es`. */
    internal val policyTag: String get() = code + if (wordTimestamps) "-wt" else ""
}

/**
 * The language allowlist, as a pure function of the podcast's declared language.
 *
 * `Podcast.podcastLanguage` is free-form server data (`"en"`, `"es-us"`, `"pt-BR"`, `""`), while
 * the catalog matches only exact language codes or English names. Reducing to the primary subtag is
 * what makes `"es-us"` work; anything outside the allowlist returns null and the episode is left to
 * the audio analyzer exactly as an unsupported language is today.
 */
internal fun speechLanguage(podcastLanguage: String?): MoonshineLanguage? {
    val normalised = podcastLanguage?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val primary = normalised.substringBefore('-').substringBefore('_')
    return MoonshineLanguage.entries.firstOrNull { it.code == primary }
}

/** The outcome of making sure a language's model is on the device. */
sealed interface ModelDownload {
    /** Every file was already there, which is the normal case for a private build. */
    data object Present : ModelDownload

    data class Downloaded(val bytes: Long, val wallMs: Long) : ModelDownload

    /** The model is missing and the only way to get it right now would be over metered data. */
    data object NeedsUnmeteredNetwork : ModelDownload
}

/**
 * The per-language model cache: the only place in the app that knows where Moonshine keeps its
 * files and whether they are there.
 *
 * Downloads are a fallback, not the normal path. [ensureDownloaded] checks for the files first and
 * only then looks at the network, so an installed model never causes a request. The one network
 * gate that does exist applies to the download alone; it is not a condition for transcription.
 */
@Singleton
class MoonshineModels @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val downloader by lazy { AssetDownloader() }

    /**
     * The directory [ai.moonshine.voice.Transcriber.loadFromFiles] is pointed at. Created on
     * demand, under `noBackupFilesDir` so 74.5 MiB of re-downloadable weights are never backed up.
     */
    fun directory(language: MoonshineLanguage): File = ModelCache.directoryFor(context, language.spec(), null)

    /** True when every file of the model is present. Never touches the network. */
    fun isPresent(language: MoonshineLanguage): Boolean = runCatching {
        downloader.isModelPresent(directory(language), language.spec())
    }.getOrElse {
        LogBuffer.e(TAG, it, "Could not check the Moonshine ${language.code} model")
        false
    }

    /** True when the active network may be used for a model download. */
    fun canDownload(): Boolean = !isActiveNetworkMetered(context)

    /**
     * Makes sure [language]'s model is on disk, downloading only what is missing.
     *
     * Blocking network IO, so it runs on [Dispatchers.IO]; a killed download leaves `.part` files
     * and resumes per file. The metered check happens before the first request, so a phone on
     * cellular reports [ModelDownload.NeedsUnmeteredNetwork] instead of spending the allowance.
     */
    suspend fun ensureDownloaded(
        language: MoonshineLanguage,
        onProgress: (ModelDownloadProgress) -> Unit = {},
    ): ModelDownload = withContext(Dispatchers.IO) {
        if (isPresent(language)) return@withContext ModelDownload.Present
        if (!canDownload()) {
            LogBuffer.w(
                TAG,
                "Moonshine ${language.code} model missing at ${directory(language)} and the network is metered, " +
                    "waiting for Wi-Fi or an install push",
            )
            return@withContext ModelDownload.NeedsUnmeteredNetwork
        }
        LogBuffer.i(
            TAG,
            "Moonshine ${language.code} model missing at ${directory(language)}, downloading ${language.cacheKey}",
        )
        val startedAt = System.currentTimeMillis()
        downloader.ensureModelPresent(directory(language), language.spec()) { relativePath, fileIndex, fileCount, bytesDone, bytesTotal ->
            Timber.tag(TAG).d(
                "Moonshine ${language.code} download ${fileIndex + 1}/$fileCount $relativePath: $bytesDone/$bytesTotal bytes",
            )
            onProgress(ModelDownloadProgress(bytesDone, bytesTotal))
        }
        val wallMs = System.currentTimeMillis() - startedAt
        val bytes = sizeOf(language)
        LogBuffer.i(TAG, "Moonshine ${language.code} model ready: $bytes bytes in ${wallMs}ms")
        ModelDownload.Downloaded(bytes, wallMs)
    }

    /** Bytes the model occupies on disk, for the stats line. */
    fun sizeOf(language: MoonshineLanguage): Long = directory(language)
        .walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    companion object {
        /**
         * The AAR version the model files come from. Bumping it invalidates every on-device
         * transcript through [engineVersion].
         */
        const val VERSION = "0.1.6"

        /**
         * The single identity stamped on an analysis: engine, model architecture and the
         * word-timestamp policy of every allowed language, so adding a language or flipping a
         * policy changes it without anyone remembering to edit a string by hand.
         */
        val engineVersion: String = "moonshine-$VERSION/tiny-streaming-" +
            MoonshineLanguage.entries.joinToString(",") { it.policyTag }
    }
}

/**
 * Whether the active network is metered. A missing answer (no network, or no service object) counts
 * as metered, because the caller only asks when it is about to move tens of megabytes.
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

/** The reason reported when a language's model is missing and cannot be fetched right now. */
internal fun modelMissingMessage(language: MoonshineLanguage, directory: File): String = "the ${language.code} speech model is not installed at ${directory.absolutePath} and was not downloaded"

private const val TAG = "MoonshineStt"
