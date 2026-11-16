package au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine

import ai.moonshine.voice.TranscriptLine
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptCue
import kotlin.math.roundToLong

/**
 * Turns Moonshine's streaming lines into the cues stored as WebVTT.
 *
 * Each cue is one **complete** streaming line, which the model VAD delimits roughly per spoken
 * turn. Word timings, where the model publishes trustworthy ones, tighten the cue to the first and
 * last spoken word so a sentence that starts after a pause starts at the word rather than at the
 * silence before it; see [spanOf] for why they are checked first. Spanish ships no attention
 * decoder, so its lines have no words at all, and both paths end up in this one function.
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
 * The span one line covers, in milliseconds.
 *
 * Word times are used when they are trustworthy, which today they usually are **not**: the
 * streaming path in Moonshine 0.1.6 returns degenerate word timings (every word collapsed onto the
 * line start, often running backwards), while the batch path returns perfect ones. Measured on a
 * two-minute English clip: batch gives `5.95→6.59, 6.59→6.79, 6.79→7.27`, streaming gives
 * `5.95→7.02, 7.02→13.75, 13.75→10.98, 10.98→5.95` for the same audio. Using those would either
 * collapse the cue to nothing or move it onto a neighbour, so [usableWordSpan] checks the list
 * before trusting it and the line's own `startTime`/`duration` is the fallback. That fallback is
 * what Spanish always gets, since its bundle ships no attention decoder.
 */
internal fun spanOf(line: TranscriptLine): LongRange {
    usableWordSpan(line)?.let { return it }
    val startMs = secondsToMs(line.startTime)
    return startMs..secondsToMs(line.startTime + line.duration)
}

/**
 * The first word's start to the last word's end, when the word list can actually be believed.
 *
 * The list has to be non-empty, ordered, inside the line it belongs to, and long enough to be a
 * real span: a set of words that all sit on one instant, or that run backwards, is a failed
 * alignment rather than speech that took no time.
 */
private fun usableWordSpan(line: TranscriptLine): LongRange? {
    val words = line.words ?: return null
    if (words.size < 2) return null
    val lineStartMs = secondsToMs(line.startTime)
    val lineEndMs = secondsToMs(line.startTime + line.duration)
    val toleranceMs = WORD_TOLERANCE_MS
    // Starting below zero rather than at Long.MIN_VALUE keeps the first comparison from
    // overflowing; no word can start before the tolerance anyway.
    var previousEndMs = -toleranceMs
    for (word in words) {
        val startMs = secondsToMs(word.start)
        val endMs = secondsToMs(word.end)
        if (startMs > endMs) return null
        if (startMs < previousEndMs - toleranceMs) return null
        if (startMs < lineStartMs - toleranceMs || endMs > lineEndMs + toleranceMs) return null
        previousEndMs = endMs
    }
    val firstMs = secondsToMs(words.first().start)
    val lastMs = secondsToMs(words.last().end)
    // A span that covers almost none of the line is an alignment that failed rather than speech in
    // a corner of it; the line's own duration is then the better answer.
    val minimumMs = (lineEndMs - lineStartMs) * MIN_WORD_COVERAGE
    if (lastMs - firstMs < minimumMs) return null
    return firstMs..lastMs
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

/**
 * Slack allowed when checking word times against their own line, for rounding at the edges and for
 * a word that starts a frame before the line's reported start.
 */
private const val WORD_TOLERANCE_MS = 250L

/**
 * How much of a line the word span has to cover to be believed. Speech inside a line is almost
 * continuous, so a believable alignment runs most of it; a failed one lands on one instant.
 */
private const val MIN_WORD_COVERAGE = 0.25

private val TERMINAL_PUNCTUATION = setOf('.', '!', '?', '…', '。', '！', '？')

/** Quotes and brackets a sentence-ending mark can be wrapped in: `said "done."`. */
private const val CLOSING_MARKS = "\"')]}’”»"
