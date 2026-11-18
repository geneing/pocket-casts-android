package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet

import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptCue
import kotlin.math.roundToLong

/** One recognised word, already in the audio clock of the pass. */
internal data class RawSegment(val text: String, val startMs: Long, val endMs: Long, val isFinal: Boolean)

/**
 * Turns recognised words into the sentence-level cues that are stored as WebVTT.
 *
 * The recogniser does not report sentences. A pass returns words with the frame each one starts on,
 * so a word boundary is where the decoder emitted a token, not where a thought ended. Storing one cue
 * per word would put those arbitrary cuts into the transcript, the classifier prompt and the gap
 * logic, all of which were tuned on sentence-sized cues.
 *
 * A cue is therefore built from the **text**: the final words are laid end to end, the result is
 * split where a sentence actually ends, and each sentence gets its times from the words its
 * characters came from. Between two words there is no finer timing than their own boundaries, so a
 * cut inside a word is placed proportionally over that word's own span — the sentence's first and
 * last characters are still the only positions the model gave us, and the result can never leave the
 * audio those words cover.
 *
 * That proportional placement is the one invented number in this class, and it is deliberate: a
 * sentence end inside a two-word span sits where the speaker paused, and cutting at the word
 * boundary instead would move the cue. It is never used to move a cue across words, and never
 * without text to justify it.
 *
 * Only final words are used. A word inside a window's overlap is still provisional — the next window
 * may replace it — so a cue built from one would move under the transcript and, worse, under an ad
 * analysis that already scored it.
 */
internal object ParakeetCues {
    /**
     * Final, non-empty segments → cues in episode time, one cue per sentence.
     *
     * [originMs] is added to every timestamp, because a pass may start at a keyframe before its
     * resume point. [durationMs] clamps the result to the episode and is ignored when it is not
     * positive, which is what a caller does when the duration is unknown.
     */
    fun toCues(segments: List<RawSegment>, originMs: Long, durationMs: Long): List<TranscriptCue> {
        val placed = place(segments, originMs, durationMs)
        if (placed.isEmpty()) return emptyList()

        val timeline = SegmentedText(placed)
        val sentences = timeline.sentences()
        if (sentences.isEmpty()) return emptyList()

        val cues = ArrayList<TranscriptCue>(sentences.size)
        var previousEndMs = 0L
        for (sentence in sentences) {
            // A sentence's span is derived from its characters, so two sentences in one window can
            // still land on the same instant. Starting each cue where the previous one ended keeps
            // the VTT monotonic, which the parser and every consumer expect.
            val startMs = maxOf(sentence.startMs, previousEndMs)
            if (sentence.endMs <= startMs) continue
            cues.add(TranscriptCue(startMs, sentence.endMs, sentence.text))
            previousEndMs = sentence.endMs
        }
        return cues
    }

    /**
     * The final segments, in episode time and in order, clamped to the episode.
     *
     * Clamping can pull a later segment onto an earlier one; starting each at the previous segment's
     * end is what keeps the laid-out text from interleaving two stretches of speech.
     */
    private fun place(segments: List<RawSegment>, originMs: Long, durationMs: Long): List<PlacedSegment> {
        val limitMs = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        val ordered = segments.asSequence()
            .filter { it.isFinal }
            .mapNotNull { segment ->
                val text = segment.text.trim()
                if (text.isEmpty()) return@mapNotNull null
                val startMs = (segment.startMs + originMs).coerceIn(0L, limitMs)
                val endMs = (segment.endMs + originMs).coerceIn(0L, limitMs)
                if (endMs <= startMs) return@mapNotNull null
                PlacedSegment(startMs, endMs, text)
            }
            .sortedWith(compareBy({ it.startMs }, { it.endMs }))
            .toList()

        val monotonic = ArrayList<PlacedSegment>(ordered.size)
        var previousEndMs = 0L
        for (segment in ordered) {
            val startMs = maxOf(segment.startMs, previousEndMs)
            if (segment.endMs <= startMs) continue
            monotonic.add(PlacedSegment(startMs, segment.endMs, segment.text))
            previousEndMs = segment.endMs
        }
        return monotonic
    }
}

/**
 * The segments laid end to end as one string, remembering which characters came from which segment.
 *
 * The separators are single spaces and belong to no segment, so a character-to-time lookup always
 * lands in real text. A sentence that spans three windows takes its start from the first window's
 * characters and its end from the last one's, which is what makes the times track the audio instead
 * of the decoder's chunking.
 */
private class SegmentedText(private val segments: List<PlacedSegment>) {
    private val text = StringBuilder()
    private val spans = ArrayList<CharSpan>(segments.size)

    init {
        for (segment in segments) {
            if (text.isNotEmpty()) text.append(' ')
            val from = text.length
            text.append(segment.text)
            spans.add(CharSpan(from, text.length, segment.startMs, segment.endMs))
        }
    }

    /**
     * The text split after each sentence-ending mark, with the span of the characters it contains.
     *
     * A trailing piece with no sentence end is folded into the sentence before it: the decoder's last
     * window usually stops mid-sentence, and a cue made of the remaining three words would be the
     * window boundary leaking into the transcript. When there is no earlier sentence, the piece is
     * kept — a pass over a single utterance can legitimately produce unpunctuated text.
     */
    fun sentences(): List<Sentence> {
        val content = text.toString()
        if (content.isEmpty()) return emptyList()

        val cuts = ArrayList<Int>(4)
        var index = 0
        while (index < content.length) {
            if (content[index] in TERMINAL_PUNCTUATION) {
                var cut = index + 1
                while (cut < content.length && content[cut] in CLOSING_MARKS) cut++
                cuts.add(cut)
                index = cut
            } else {
                index++
            }
        }
        if (cuts.isEmpty() || cuts.last() < content.length) cuts.add(content.length)

        val sentences = ArrayList<Sentence>(cuts.size)
        var from = 0
        for (cut in cuts) {
            val raw = content.substring(from, cut)
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty()) {
                val leading = raw.indexOfFirst { !it.isWhitespace() }
                val startChar = from + leading
                val endChar = startChar + trimmed.length - 1
                sentences.add(Sentence(timeAt(startChar), endAt(endChar), trimmed))
            }
            from = cut
        }
        if (sentences.isEmpty()) return emptyList()

        // The decoder's last window usually stops mid-sentence: "…a rare dish." arrives as
        // "The leg is a rare" and "dish." only by luck. A trailing piece without punctuation is
        // folded into the sentence before it, so the window boundary does not leak into the
        // transcript as a three-word cue. A pass over one unpunctuated utterance stays one cue.
        val last = sentences.last()
        if (sentences.size > 1 && !last.text.endsSentence()) {
            val previous = sentences[sentences.size - 2]
            sentences[sentences.size - 2] = previous.copy(
                endMs = maxOf(previous.endMs, last.endMs),
                text = "${previous.text} ${last.text}",
            )
            sentences.removeAt(sentences.lastIndex)
        }
        return sentences
    }

    /** Where the character at [index] starts, interpolated inside the segment that owns it. */
    private fun timeAt(index: Int): Long = interpolate(index, exclusiveEnd = false)

    /** Where the character at [index] ends, so a cue covers its last character rather than touching it. */
    private fun endAt(index: Int): Long = interpolate(index, exclusiveEnd = true)

    private fun interpolate(index: Int, exclusiveEnd: Boolean): Long {
        val span = spans.firstOrNull { index >= it.fromChar && index < it.toChar } ?: return segments.last().endMs
        val characters = span.toChar - span.fromChar
        if (characters <= 0) return span.startMs
        val offset = index - span.fromChar + if (exclusiveEnd) 1 else 0
        val fraction = offset.toDouble() / characters
        return span.startMs + ((span.endMs - span.startMs) * fraction).roundToLong()
    }

    private data class CharSpan(val fromChar: Int, val toChar: Int, val startMs: Long, val endMs: Long)

    data class Sentence(val startMs: Long, val endMs: Long, val text: String)
}

/** True when the text ends a sentence, ignoring a closing quote or bracket after the punctuation. */
private fun String.endsSentence(): Boolean {
    val trimmed = trimEnd { it in CLOSING_MARKS }
    return trimmed.lastOrNull()?.let { it in TERMINAL_PUNCTUATION } == true
}

private data class PlacedSegment(val startMs: Long, val endMs: Long, val text: String)

private val TERMINAL_PUNCTUATION = setOf('.', '!', '?', '…', '。', '！', '？')

/** Quotes and brackets a sentence-ending mark can be wrapped in: `said "done."`. */
private const val CLOSING_MARKS = "\"')]}’”»"
