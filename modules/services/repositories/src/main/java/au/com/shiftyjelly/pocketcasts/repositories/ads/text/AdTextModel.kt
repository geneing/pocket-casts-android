package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import com.squareup.moshi.Moshi
import java.util.Locale

/**
 * Transcript-only ad detector: text in, ad pods out. No network, no sibling takes, no
 * per-episode tuning. The phone runs the same model and decoder that `m0/train.py` exported.
 */
class AdTextModel internal constructor(
    private val vectorizer: TfidfTextVectorizer,
    private val decoder: AdDetectorBundle.Decoder,
    /** Bundle version + vocabulary hash; analyses from another identity are stale. */
    val identity: String,
) {
    fun probabilities(segments: List<AdTranscriptSegment>): DoubleArray {
        if (segments.isEmpty()) return DoubleArray(0)
        val normalized = segments.map { normalize(it.text) }
        return DoubleArray(segments.size) { i ->
            val previous = if (i > 0) normalized[i - 1] else ""
            val following = if (i + 1 < normalized.size) normalized[i + 1] else ""
            val current = normalized[i]
            vectorizer.score("$current $current $previous $following")
        }
    }

    fun detect(segments: List<AdTranscriptSegment>): List<DetectedAdPod> {
        if (segments.isEmpty()) return emptyList()
        val ordered = segments.sortedBy { it.startSeconds }
        return AdPodDecoder.decode(ordered, probabilities(ordered), decoder)
    }

    /** Scores a pre-built document exactly as the Python trainer does (for parity fixtures). */
    fun scoreDocument(document: String): Double = vectorizer.score(document)

    fun decoder(): AdDetectorBundle.Decoder = decoder

    companion object {
        private val whitespace = Regex("\\s+")

        fun normalize(text: String): String = text.trim().replace(whitespace, " ").lowercase(Locale.ROOT)

        fun fromJson(json: String): AdTextModel {
            val bundle = requireNotNull(Moshi.Builder().build().adapter(AdDetectorBundle::class.java).fromJson(json)) {
                "empty ad detector bundle"
            }
            require(bundle.word.idf.size == bundle.word.vocab.size) { "word vocabulary/idf size mismatch" }
            require(bundle.char.idf.size == bundle.char.vocab.size) { "char vocabulary/idf size mismatch" }
            require(bundle.classifier.weights.size == bundle.word.vocab.size + bundle.char.vocab.size) {
                "classifier weights do not cover both feature blocks"
            }
            return AdTextModel(TfidfTextVectorizer(bundle), bundle.decoder, "${bundle.version}:${bundle.vocabHash}")
        }
    }
}
