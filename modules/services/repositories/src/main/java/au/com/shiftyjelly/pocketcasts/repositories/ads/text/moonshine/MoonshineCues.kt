package au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine

import ai.moonshine.voice.TranscriptLine
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptCue
import kotlin.math.roundToLong

/**
 * Turns Moonshine's streaming lines into the cues stored as WebVTT.
 *
 * Each cue is one **complete** streaming line, which the model VAD delimits roughly per spoken
 * turn. Word timings, where the model publishes them (English), tighten the cue to the first and
 * last spoken word so a sentence that starts after a pause starts at the word rather than at the
 * silence before it. Spanish ships no attention decoder, so its lines have no words and the cue
 * falls back to the line's own start and duration; both paths end up in this one function.
 *
 * Only complete lines are used: partial lines have no words yet and a provisional duration, so a
 * cue built from one would be rewritten on the next update. With the library's default of not
 * decoding incomplete lines, a completed line is final.
 *
 * Rejected: one cue per word. It multiplies the stored cues, and the classifier's prompt, pod
 * minimum and gap logic were all tuned on sentence-sized cues.
 */
internal fun cues(lines: List<TranscriptLine>, durationMs: Long): List<TranscriptCue> {
    val placed = lines.asSequence()
        .filter { it.isComplete }
        .mapNotNull { line ->
            val text = line.text?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val span = spanOf(line)
            PlacedLine(span.first, span.last, text)
        }
        .sortedWith(compareBy({ it.startMs }, { it.endMs }))
        .toList()

    val limitMs = if (durationMs > 0L) durationMs else Long.MAX_VALUE
    val clamped = ArrayList<PlacedLine>(placed.size)
    var previousEndMs = 0L
    for (line in placed) {
        val startMs = line.startMs.coerceIn(0L, limitMs)
        val endMs = line.endMs.coerceIn(0L, limitMs)
        if (endMs <= startMs) continue
        // Clamping can pull a later cue onto an earlier one; starting where the previous cue ended
        // keeps the VTT monotonic, which the parser and every consumer expects.
        val overlapFreeStart = maxOf(startMs, previousEndMs)
        if (endMs <= overlapFreeStart) continue
        clamped.add(PlacedLine(overlapFreeStart, endMs, line.text))
        previousEndMs = endMs
    }

    return mergeSplitSentences(clamped).map { TranscriptCue(it.startMs, it.endMs, it.text) }
}

/**
 * The span one line covers, in milliseconds. Words win when present; otherwise the line's own
 * `startTime` and `duration` are used, which is all Spanish has.
 */
internal fun spanOf(line: TranscriptLine): LongRange {
    val words = line.words ?: emptyList()
    val startMs = words.firstOrNull()?.start?.let(::secondsToMs) ?: secondsToMs(line.startTime)
    val endMs = words.lastOrNull()?.end?.let(::secondsToMs) ?: secondsToMs(line.startTime + line.duration)
    return startMs..endMs
}

/**
 * Folds a line that continues the previous one back into it.
 *
 * The streaming decoder restarts a line after a mid-sentence VAD blip, which would otherwise cut a
 * sentence in two at the caption level. A cue is folded into its predecessor when the predecessor
 * has not ended its sentence and the gap between them is shorter than [MERGE_GAP_MS]; anything
 * longer is a real pause and stays two cues.
 */
private fun mergeSplitSentences(lines: List<PlacedLine>): List<PlacedLine> {
    val merged = ArrayList<PlacedLine>(lines.size)
    for (line in lines) {
        val previous = merged.lastOrNull()
        if (previous != null &&
            line.startMs - previous.endMs < MERGE_GAP_MS &&
            !previous.text.endsSentence()
        ) {
            merged[merged.lastIndex] = previous.copy(endMs = line.endMs, text = "${previous.text} ${line.text}")
        } else {
            merged.add(line)
        }
    }
    return merged
}

/** True when the text ends a sentence, ignoring a closing quote or bracket after the punctuation. */
private fun String.endsSentence(): Boolean {
    val trimmed = trimEnd { it in CLOSING_MARKS }
    return trimmed.lastOrNull()?.let { it in TERMINAL_PUNCTUATION } == true
}

private fun secondsToMs(seconds: Float): Long = (seconds.toDouble() * 1000.0).roundToLong()

private data class PlacedLine(val startMs: Long, val endMs: Long, val text: String)

/** The gap below which two lines are read as one interrupted sentence. */
private const val MERGE_GAP_MS = 150L

private val TERMINAL_PUNCTUATION = setOf('.', '!', '?', '…', '。', '！', '？')

/** Quotes and brackets a sentence-ending mark can be wrapped in: `said "done."`. */
private const val CLOSING_MARKS = "\"')]}’”»"
