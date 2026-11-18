package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.litert

import org.json.JSONObject

/**
 * Detokenises Parakeet's token ids into words.
 *
 * The bundle used to carry its tokenizer and MediaPipe ran it for us; the `.tflite` graphs stop at
 * token ids, so the mapping has to exist here. It is deliberately only a decoder: recognition only
 * ever needs ids to text, and the vocabulary is a plain id → piece table from the same
 * `tokenizer.json` the model was converted with (8 192 pieces, word starts marked with `▁`), so the
 * file can be read straight off disk without a native library or a second copy of the table.
 *
 * Control tokens (`<unk>`, `<pad>` and the `<|...|>` family that used to steer Whisper-style
 * decoding) are dropped rather than rendered: they are not speech, and a transcript that contains
 * them would be analysed as if they were.
 */
internal class ParakeetTokenizer(private val pieces: Array<String>) {

    /** How one token joins the word being built. */
    internal class Piece(val text: String, val startsWord: Boolean)

    /** The id's piece, or null for one that must not appear in the transcript. */
    fun piece(id: Int): Piece? {
        if (id < 0 || id >= pieces.size) return null
        val raw = pieces[id]
        if (raw.isEmpty() || raw.isControl()) return null
        val startsWord = raw.startsWith(WORD_MARKER)
        val text = raw.replace(WORD_MARKER, ' ').trim()
        return if (text.isEmpty()) null else Piece(text, startsWord)
    }

    /** The whole sequence as text, which is what the debug lines log. */
    fun decode(ids: IntArray): String {
        val builder = StringBuilder()
        for (id in ids) {
            val piece = piece(id) ?: continue
            if (piece.startsWord && builder.isNotEmpty() && builder.last() != ' ') builder.append(' ')
            builder.append(piece.text)
        }
        return builder.toString().trim()
    }

    companion object {
        /** The marker SentencePiece uses for a space that begins a word. */
        private const val WORD_MARKER = '\u2581'

        /** A token that encodes a rule rather than language: `<unk>`, `<pad>`, `<|nospeech|>`… */
        private fun String.isControl() = length > 2 && startsWith('<') && endsWith('>')

        /**
         * Reads the id → piece table out of a Hugging Face `tokenizer.json`.
         *
         * The file lists `model.vocab` as piece → id, so the table is rebuilt by id; an id the file
         * never mentions stays empty and is skipped by [piece]. Reading it is a few megabytes of
         * parsing once per pass, which is nothing next to loading the bundle.
         */
        fun parse(json: String): ParakeetTokenizer {
            val vocab = JSONObject(json).getJSONObject("model").getJSONObject("vocab")
            var highest = -1
            val ids = HashMap<String, Int>(vocab.length() * 2)
            for (piece in vocab.keys()) {
                val id = vocab.getInt(piece)
                ids[piece] = id
                if (id > highest) highest = id
            }
            val pieces = Array(highest + 1) { "" }
            for ((piece, id) in ids) pieces[id] = piece
            return ParakeetTokenizer(pieces)
        }
    }
}
