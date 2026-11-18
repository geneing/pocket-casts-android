package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParakeetTokenizerTest {
    /** Ids are the array positions, which is how the graph's logits are laid out. */
    private val tokenizer = ParakeetTokenizer(
        arrayOf("<unk>", "\u2581the", "\u2581qu", "ick", "\u2581fox", "<|nospeech|>", "<pad>"),
    )

    @Test
    fun `a leading marker starts a word and the marker is not part of the text`() {
        val piece = tokenizer.piece(1)!!

        assertEquals("the", piece.text)
        assertEquals(true, piece.startsWord)
    }

    @Test
    fun `a piece without a marker continues the word before it`() {
        val piece = tokenizer.piece(3)!!

        assertEquals("ick", piece.text)
        assertEquals(false, piece.startsWord)
    }

    @Test
    fun `control tokens never reach the transcript`() {
        assertNull("the unknown token is not speech", tokenizer.piece(0))
        assertNull("a control token must not be read as a word", tokenizer.piece(5))
        assertNull(tokenizer.piece(6))
        // Out of range ids are the model's problem, not a reason to crash a pass.
        assertNull(tokenizer.piece(-1))
        assertNull(tokenizer.piece(99))
    }

    @Test
    fun `a sequence decodes to one space separated string`() {
        assertEquals("the quick fox", tokenizer.decode(intArrayOf(1, 2, 3, 4)))
        assertEquals("quick fox", tokenizer.decode(intArrayOf(2, 3, 0, 4)))
        assertEquals("", tokenizer.decode(intArrayOf(0, 5, 6)))
    }
}
