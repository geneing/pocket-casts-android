package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Inference bundle for the transcript TF-IDF ad detector, produced by
 * `dev/adaware/next/m0/train.py`. Everything the phone needs is in this file; no Python,
 * no scikit-learn, no per-show tuning.
 */
@JsonClass(generateAdapter = true)
data class AdDetectorBundle(
    val version: String,
    @Json(name = "vocab_hash") val vocabHash: String,
    val word: FeatureBlock,
    val char: FeatureBlock,
    val classifier: Classifier,
    val decoder: Decoder,
) {
    @JsonClass(generateAdapter = true)
    data class FeatureBlock(
        val analyzer: String,
        val ngram: List<Int>,
        @Json(name = "min_df") val minDf: Int,
        @Json(name = "max_features") val maxFeatures: Int,
        @Json(name = "sublinear_tf") val sublinearTf: Boolean,
        val vocab: List<String>,
        val idf: List<Double>,
        val offset: Int,
    )

    @JsonClass(generateAdapter = true)
    data class Classifier(
        val weights: List<Double>,
        val intercept: Double,
        @Json(name = "positive_prior") val positivePrior: Double,
    )

    @JsonClass(generateAdapter = true)
    data class Decoder(
        val threshold: Double,
        @Json(name = "boundary_penalty") val boundaryPenalty: Double,
        @Json(name = "duration_weight") val durationWeight: Double,
        @Json(name = "merge_gap") val mergeGap: Double,
        @Json(name = "min_pod_seconds") val minPodSeconds: Double,
        @Json(name = "max_pod_seconds") val maxPodSeconds: Double,
        @Json(name = "hard_gap_seconds") val hardGapSeconds: Double,
    )
}

/** One transcript cue in the media timeline. */
data class AdTranscriptSegment(
    val startSeconds: Double,
    val endSeconds: Double,
    val text: String,
)

/** A decoded ad pod in the media timeline. */
data class DetectedAdPod(
    val startMs: Long,
    val endMs: Long,
    val meanProbability: Double,
)
