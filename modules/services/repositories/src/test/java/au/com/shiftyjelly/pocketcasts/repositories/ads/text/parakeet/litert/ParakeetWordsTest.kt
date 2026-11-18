package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert

import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert.ParakeetRecognizer.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between two overlapping windows: which words are final, and which copy of a repeated word
 * survives.
 *
 * This is where the 2 s overlap turns into a transcript, so a mistake here shows up as a stutter or a
 * duplicated word in a third of the cues of every episode.
 */
class ParakeetWordsTest {
    private val tokenizer = ParakeetTokenizer(
        arrayOf("<unk>", "\u2581the", "\u2581qu", "ick", "\u2581fox", "\u2581brown"),
    )
    private val words = ParakeetWords(tokenizer)

    @Test
    fun `nothing is final until a later window starts after it`() {
        val first = words.accept(windowStartMs = 0L, tokens = listOf(Token(1, 0), Token(2, 1), Token(3, 1)))

        assertTrue("a word in the overlap can still be revised", first.isEmpty())
        assertEquals(listOf("the", "quick"), words.finish().map { it.text })
    }

    @Test
    fun `a subword token is joined to the word it belongs to`() {
        words.accept(0L, listOf(Token(2, 1), Token(3, 1)))
        val confirmed = words.accept(3_000L, emptyList())

        // Both tokens sit on frame 1, so the word is one 80 ms slot, not two words.
        assertEquals(1, confirmed.size)
        assertEquals("quick", confirmed.single().text)
        assertEquals(80L, confirmed.single().startMs)
        assertEquals(160L, confirmed.single().endMs)
    }

    @Test
    fun `words are placed at their frame time in the pass`() {
        words.accept(0L, listOf(Token(1, 0)))
        val confirmed = words.accept(3_000L, listOf(Token(4, 36), Token(5, 37)))

        assertEquals("the", confirmed.single().text)
        // Frame 36 of a window that starts at 3 s is 3 s + 36 x 80 ms.
        assertEquals(listOf(3_000L + 36 * 80L, 3_000L + 37 * 80L), words.finish().map { it.startMs })
    }

    @Test
    fun `a word the next window repeats is not written twice`() {
        // The first window hears "fox" just before the second window's start; the second window hears
        // it again at its own start. Same word, so only the first copy is stored.
        words.accept(0L, listOf(Token(4, 36)))
        val confirmed = words.accept(3_000L, listOf(Token(4, 0)))

        assertEquals(listOf("fox"), confirmed.map { it.text })
        assertTrue("the repeat is dropped, not appended", words.finish().isEmpty())
    }

    @Test
    fun `a window with no speech contributes nothing`() {
        words.accept(0L, emptyList())
        assertTrue(words.accept(3_000L, emptyList()).isEmpty())
        assertTrue(words.finish().isEmpty())
    }

    @Test
    fun `a control token is skipped without breaking the word before it`() {
        words.accept(0L, listOf(Token(4, 0), Token(0, 1)))
        val confirmed = words.accept(3_000L, emptyList())

        assertEquals(listOf("fox"), confirmed.map { it.text })
    }
}
