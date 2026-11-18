package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert

import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.RawSegment
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert.ParakeetRecognizer.Token
import java.util.Locale

/**
 * Turns token ids into words with times, and stitches those words across overlapping windows.
 *
 * The bundle's sliding window is 5 s with a 2 s overlap, so every window re-recognises the last 2 s
 * of the one before it. MediaPipe hid that behind `TimestampTextMerger`; here the merge is done on
 * the times the decoder already reports. A word is only final once a later window starts after it:
 * that window has more left context and cannot see the word again, so nothing can revise it after
 * that point. Words still inside the overlap wait for the next window's version, which is the whole
 * reason the seam does not stutter.
 */
internal class ParakeetWords(
    private val tokenizer: ParakeetTokenizer,
) {
    /** One recognised word in pass time. */
    data class Word(val text: String, val startMs: Long, val endMs: Long)

    private var pending: List<Word> = emptyList()
    private var lastConfirmedEndMs = 0L
    private var lastConfirmedText = ""

    /**
     * Adds one window's tokens and returns the words that just became final.
     *
     * [windowStartMs] is where the window starts in the pass, because the tokens' frames are
     * relative to it.
     */
    fun accept(windowStartMs: Long, tokens: List<Token>): List<RawSegment> {
        val words = wordsOf(windowStartMs, tokens)
        val confirmed = pending.filter { it.startMs < windowStartMs }.mapNotNull(::confirm)
        pending = words
        return confirmed
    }

    /** The tail of the pass: every word still waiting for a window that never comes. */
    fun finish(): List<RawSegment> = pending.mapNotNull(::confirm).also { pending = emptyList() }

    private fun toSegment(word: Word): RawSegment = RawSegment(
        text = word.text,
        startMs = word.startMs,
        endMs = word.endMs,
        isFinal = true,
    )

    private fun confirm(word: Word): RawSegment? {
        val canonical = word.text.lowercase(Locale.US).trim()
        if (canonical.isEmpty()) return null
        // The same word can be reported by two windows a few tens of milliseconds apart. Keeping
        // the later copy would put two identical words side by side in the transcript, and keeping
        // the earlier one would cut the sentence in half, so the duplicate is dropped whole.
        if (canonical == lastConfirmedText && word.startMs < lastConfirmedEndMs + DUPLICATE_WINDOW_MS) return null
        val startMs = maxOf(word.startMs, lastConfirmedEndMs)
        if (word.endMs <= startMs) return null
        lastConfirmedEndMs = word.endMs
        lastConfirmedText = canonical
        return toSegment(Word(word.text, startMs, word.endMs))
    }

    /** One window's tokens as words; a token that starts a word ends the one before it. */
    private fun wordsOf(windowStartMs: Long, tokens: List<Token>): List<Word> {
        val words = ArrayList<Word>(tokens.size)
        val current = StringBuilder()
        var currentStartMs = 0L
        var currentEndMs = 0L

        fun flush() {
            if (current.isEmpty()) return
            words.add(Word(current.toString(), currentStartMs, currentEndMs))
            current.clear()
        }

        for (token in tokens) {
            val piece = tokenizer.piece(token.id) ?: continue
            val atMs = windowStartMs + token.frame * FRAME_MS
            if (piece.startsWord) flush()
            if (current.isEmpty()) currentStartMs = atMs
            current.append(piece.text)
            currentEndMs = atMs + FRAME_MS
        }
        flush()
        return words
    }

    companion object {
        /**
         * One encoder frame of audio: the mel front end hops 10 ms and the encoder subsamples 8x, so
         * a token's frame index is a multiple of 80 ms in the window.
         */
        const val FRAME_MS = 80L

        /** How close two reports of the same word must be to be the same word. */
        private const val DUPLICATE_WINDOW_MS = 400L
    }
}
