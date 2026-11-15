package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import java.io.File

/** A transcript cue produced on device, in the media timeline. */
data class TranscriptCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * The engine version stamped on a transcript made before the engine reported its own identity.
 * Rows written by current builds carry [EpisodeTranscriber.version] instead; this stays only so
 * those older rows are still recognised as on-device transcripts instead of being thrown away.
 */
internal const val ON_DEVICE_TRANSCRIPT_VERSION = "on-device-transcript/0"

sealed interface TranscriptResult {
    data class Success(val cues: List<TranscriptCue>, val engine: String) : TranscriptResult
    data class Unavailable(val reason: String) : TranscriptResult
    data class Failed(val message: String) : TranscriptResult
}

/** Where the audio being transcribed comes from. */
sealed interface TranscriptSource {
    /** A downloaded episode file. */
    data class Downloaded(val file: File) : TranscriptSource

    /**
     * An episode played from its stream; the recognizer fetches it, nothing is stored locally.
     *
     * [isHls] marks a playlist URL: the fetcher is a plain `GET`, so downloading one would store a
     * `.m3u8` text file that no decoder can open. Such an episode is reported as unavailable rather
     * than "decoded" into nonsense.
     */
    data class Stream(val url: String, val isHls: Boolean = false) : TranscriptSource
}

/**
 * Everything a single transcription pass needs, resolved by the caller.
 *
 * The language travels with the request rather than being looked up here: the engine must stay
 * free of database access so a pass can be run from a worker, a test or a benchmark with nothing
 * but a file. [podcastLanguage] is the podcast's declared language, free-form server data such as
 * `"en"`, `"es-us"` or `"pt-BR"`.
 */
data class TranscriptRequest(
    val episodeUuid: String,
    val source: TranscriptSource,
    val durationMs: Long,
    val podcastLanguage: String?,
)

/**
 * Whether a pass for one episode can start, answered before any audio is decoded or any model
 * bytes are fetched, so the scheduler can skip work that would only sit waiting for Wi-Fi.
 */
enum class TranscriptAvailability {
    /** The engine and the episode's language's model are usable now. */
    Ready,

    /** The engine cannot run on this device at all, which is the case below Android 8. */
    EngineUnavailable,

    /** No model is published for the episode's language, so the audio analyzer covers the episode. */
    UnsupportedLanguage,

    /**
     * The language needs a model that is not on the device and the active network is metered. A
     * pass is refused instead of spending tens of megabytes of someone's data allowance.
     */
    ModelNeedsUnmeteredNetwork,
}

/** How far along a model download is, for progress reporting. */
data class ModelDownloadProgress(val bytesDone: Long, val bytesTotal: Long) {
    val fraction: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

/**
 * On-device speech-to-text for an episode. Implementations return timestamps in the timeline of
 * the audio they were given, which is the episode timeline in both cases.
 */
interface EpisodeTranscriber {
    /**
     * Identifies the recognizer and its settings, covering the engine version, the model and its
     * architecture, and the timestamp policy. Analyses made with another version are stale.
     */
    val version: String

    /**
     * A cheap, device-level answer: can the engine run at all here? Implementations must not do
     * per-episode work, because the scheduler asks once per app launch.
     */
    fun isAvailable(): Boolean

    /**
     * Whether a pass for an episode in [podcastLanguage] can start now. Checks the allowlist and
     * whether the model is already on the device; it never downloads, and only reports a metered
     * network when a download would actually be needed.
     */
    fun availability(podcastLanguage: String?): TranscriptAvailability

    /**
     * Transcribes [TranscriptRequest.source]. Reports model download progress through
     * [onModelProgress] so a screen or a worker can show that the first pass is fetching weights
     * rather than stuck.
     */
    suspend fun transcribe(
        request: TranscriptRequest,
        onModelProgress: (ModelDownloadProgress) -> Unit = {},
    ): TranscriptResult
}
