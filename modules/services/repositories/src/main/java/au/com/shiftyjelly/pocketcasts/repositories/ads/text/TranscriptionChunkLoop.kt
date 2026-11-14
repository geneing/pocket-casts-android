package au.com.shiftyjelly.pocketcasts.repositories.ads.text

/** One chunk of 16-bit PCM and where it starts in the episode. */
internal class PcmChunk(val samples: ShortArray, val offsetMs: Long)

/** Why a chunk ended the way it did. `NoSpeech` is normal; `Fatal` invalidates the pass. */
internal sealed interface ChunkResult {
    data class Speech(val cues: List<ChunkCue>) : ChunkResult
    data object NoSpeech : ChunkResult
    data object Unavailable : ChunkResult
    data class Fatal(val message: String) : ChunkResult
}

internal data class ChunkCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Drives chunked recognition and decides the outcome of the whole pass.
 *
 * A chunk with no speech is expected (music, silence, a break) and does not fail the episode.
 * A fatal error does: returning a transcript built from the chunks that happened to succeed
 * would let [TranscriptAdAnalyzer] cache a partial transcript as a finished pass.
 * The `close` callback always runs, including on cancellation.
 */
internal class TranscriptionChunkLoop(
    private val recognize: suspend (PcmChunk) -> ChunkResult,
    private val close: () -> Unit,
) {
    suspend fun run(chunkCount: Int, chunkAt: (Int) -> PcmChunk): TranscriptResult {
        val cues = ArrayList<TranscriptCue>()
        try {
            for (index in 0 until chunkCount) {
                val chunk = chunkAt(index)
                when (val result = recognize(chunk)) {
                    is ChunkResult.Speech -> result.cues.forEach { cue ->
                        cues.add(TranscriptCue(chunk.offsetMs + cue.startMs, chunk.offsetMs + cue.endMs, cue.text))
                    }

                    ChunkResult.NoSpeech -> Unit

                    ChunkResult.Unavailable -> return TranscriptResult.Unavailable("recognizer unavailable")

                    is ChunkResult.Fatal -> return TranscriptResult.Failed(result.message)
                }
            }
            return if (cues.isEmpty()) {
                TranscriptResult.Failed("recognizer returned no speech")
            } else {
                TranscriptResult.Success(cues.sortedBy { it.startMs }, ENGINE)
            }
        } finally {
            close()
        }
    }

    companion object {
        const val ENGINE = "android.ondevice.speech/1"
    }
}
