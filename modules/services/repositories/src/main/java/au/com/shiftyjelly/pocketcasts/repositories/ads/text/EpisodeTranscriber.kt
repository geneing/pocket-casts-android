package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import java.io.File

/** A transcript cue produced on device, in the media timeline. */
data class TranscriptCue(val startMs: Long, val endMs: Long, val text: String)

sealed interface TranscriptResult {
    data class Success(val cues: List<TranscriptCue>, val engine: String) : TranscriptResult
    data class Unavailable(val reason: String) : TranscriptResult
    data class Failed(val message: String) : TranscriptResult
}

/**
 * Offline speech-to-text for a downloaded episode. Implementations must not touch the network
 * and must return timestamps in the same timeline as the downloaded file.
 */
interface EpisodeTranscriber {
    /** Identifies the recognizer and its settings; analyses made with another version are stale. */
    val version: String

    fun isAvailable(): Boolean

    suspend fun transcribe(episodeUuid: String, file: File, durationMs: Long): TranscriptResult
}
