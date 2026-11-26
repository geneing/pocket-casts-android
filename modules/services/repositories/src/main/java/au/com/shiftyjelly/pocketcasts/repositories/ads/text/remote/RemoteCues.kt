package au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote

import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptCue
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.ParakeetCues
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.RawSegment
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import kotlin.math.roundToLong

/**
 * Turns one remote response into the sentence-level cues the rest of the app already stores.
 *
 * The engine returns words with their own start and end, so the words are the input to
 * [ParakeetCues]: the same sentence builder the on-device pass uses, which keeps the cue shape the
 * classifier prompt and the gap logic were tuned on identical whichever engine produced the text.
 * A response without words (a provider that omits them) falls back to its own segments, which are
 * already sentence-sized, and a response with only `text` becomes one cue over the window: losing a
 * transcript because the shape is thinner than expected would fail the episode for good.
 *
 * [originMs] is the episode time of the chunk's first sample, because the model's timestamps start
 * at zero for every request. Only cues that *start* in `[fromMs, untilMs)` are kept: the chunk's
 * audio extends past that window, so the sentence spoken across a seam is completed by the chunk
 * that started before it and dropped by the next one, instead of appearing twice or losing its tail.
 *
 * Two limits keep the chunks from writing over each other:
 *  - a cue cannot end past `originMs + audioMs`, the audio that was actually sent. The model happily
 *    puts the last words of a window after the end of the audio it was given, and a cue that ends
 *    four seconds into the next chunk is a cue the next chunk also speaks.
 *  - a cue cannot start before [notBeforeMs], where the previous chunk's cues ended. A sentence that
 *    starts just inside this chunk's window may still be covered by the previous chunk's last
 *    sentence; starting at the earlier end keeps the transcript monotonic, which the VTT parser, the
 *    transcript tab and the ad analysis all assume.
 */
internal object RemoteCues {
    fun toCues(
        transcript: RemoteTranscript,
        originMs: Long,
        fromMs: Long,
        untilMs: Long,
        durationMs: Long,
        /** The audio this request carried, so a cue cannot be placed past what was sent. */
        audioMs: Long = 0L,
        /** Where the previous chunk's cues ended; a cue cannot start before it. */
        notBeforeMs: Long = 0L,
    ): List<TranscriptCue> {
        val limitMs = minOf(
            if (durationMs > 0L) durationMs else Long.MAX_VALUE,
            if (audioMs > 0L) originMs + audioMs else Long.MAX_VALUE,
        )
        val words = transcript.words.mapNotNull { word ->
            word.toSegment(originMs)
        }
        val segments = if (words.isNotEmpty()) {
            words
        } else {
            transcript.segments.mapNotNull { segment -> segment.toSegment(originMs) }
        }
        if (segments.isEmpty()) return textOnlyCue(transcript, originMs, fromMs, untilMs, limitMs, notBeforeMs)
        warnIfPastTheAudio(segments, limitMs, originMs)
        return ParakeetCues.toCues(segments, originMs = 0L, durationMs = limitMs)
            .filter { it.startMs >= fromMs && it.startMs < untilMs }
            .mapNotNull { cue ->
                val startMs = maxOf(cue.startMs, notBeforeMs)
                if (cue.endMs <= startMs) null else TranscriptCue(startMs, cue.endMs, cue.text)
            }
    }

    /**
     * The model's time for a word is its own; it is not clipped to the audio it was asked about.
     * When it places words past the end of what was sent, the clamp above brings them back, and this
     * says so once per response — a large overrun is worth seeing, because it is the difference
     * between a cue that ends where the audio did and a cue the next chunk also covers.
     */
    private fun warnIfPastTheAudio(segments: List<RawSegment>, limitMs: Long, originMs: Long) {
        val overrunMs = (segments.maxOfOrNull { it.endMs } ?: return) - limitMs
        if (overrunMs > OVERRUN_WARNING_MS) {
            LogBuffer.w(LOG_TAG, "Model placed words ${overrunMs}ms past the audio sent at ${originMs}ms")
        }
    }

    /** The whole response as one cue, for a provider that returned nothing but its text. */
    private fun textOnlyCue(
        transcript: RemoteTranscript,
        originMs: Long,
        fromMs: Long,
        untilMs: Long,
        limitMs: Long,
        notBeforeMs: Long,
    ): List<TranscriptCue> {
        val text = transcript.text.trim()
        if (text.isEmpty()) return emptyList()
        val startMs = maxOf(fromMs, originMs, notBeforeMs)
        val spokenMs = (transcript.audioSeconds * MILLIS_PER_SECOND).roundToLong()
        val endMs = minOf(
            untilMs,
            limitMs,
            if (spokenMs > 0L) originMs + spokenMs else untilMs,
        )
        return if (endMs > startMs) listOf(TranscriptCue(startMs, endMs, text)) else emptyList()
    }

    private fun RemoteWord.toSegment(originMs: Long): RawSegment? {
        val start = originMs + (startSeconds * MILLIS_PER_SECOND).roundToLong()
        val end = originMs + (endSeconds * MILLIS_PER_SECOND).roundToLong()
        return RawSegment(text = word, startMs = start, endMs = end, isFinal = true).takeIf { end > start }
    }

    private fun RemoteSegment.toSegment(originMs: Long): RawSegment? {
        val start = originMs + (startSeconds * MILLIS_PER_SECOND).roundToLong()
        val end = originMs + (endSeconds * MILLIS_PER_SECOND).roundToLong()
        return RawSegment(text = text, startMs = start, endMs = end, isFinal = true).takeIf { end > start }
    }

    private const val MILLIS_PER_SECOND = 1_000.0

    private const val LOG_TAG = "RemoteAsr"

    /** How far past the audio sent the model's last word may land before it is worth a warning. */
    private const val OVERRUN_WARNING_MS = 1_000L
}
