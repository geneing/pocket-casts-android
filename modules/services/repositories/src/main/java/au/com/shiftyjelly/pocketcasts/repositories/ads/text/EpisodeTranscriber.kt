package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import java.io.File

/** A transcript cue produced on device, in the media timeline. */
data class TranscriptCue(val startMs: Long, val endMs: Long, val text: String)

sealed interface TranscriptResult {
    data class Success(val cues: List<TranscriptCue>, val engine: String) : TranscriptResult
    data class Unavailable(val reason: String) : TranscriptResult
    data class Failed(val message: String) : TranscriptResult
}

/** Where the audio being transcribed comes from. */
sealed interface TranscriptSource {
    /** A downloaded episode file. */
    data class Downloaded(val file: File) : TranscriptSource

    /** An episode played from its stream; the recognizer fetches it, nothing is stored locally. */
    data class Stream(val url: String) : TranscriptSource
}

/**
 * On-device speech-to-text for an episode. Implementations return timestamps in the timeline of
 * the audio they were given, which is the episode timeline in both cases.
 */
interface EpisodeTranscriber {
    /** Identifies the recognizer and its settings; analyses made with another version are stale. */
    val version: String

    fun isAvailable(): Boolean

    suspend fun transcribe(episodeUuid: String, source: TranscriptSource, durationMs: Long): TranscriptResult
}
