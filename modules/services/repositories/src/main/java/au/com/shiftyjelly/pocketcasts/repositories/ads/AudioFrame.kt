package au.com.shiftyjelly.pocketcasts.repositories.ads

/**
 * Acoustic features for one analysis frame. Everything is cheap to compute and deliberately
 * crude: the segmenter only needs changes between neighbouring windows, not absolute accuracy.
 */
internal class AudioFrame(
    val positionMs: Long,
    val rmsDb: Float,
    val zeroCrossingRate: Float,
    val spectralCentroidHz: Float,
    val spectralFlatness: Float,
    /** Inter-channel correlation in [-1, 1]. Mono or identical channels are 1. */
    val channelCorrelation: Float,
    /** Log energies of the mel bands, used as a compact spectral fingerprint. */
    val melBands: FloatArray,
)

internal data class SpliceBoundary(
    val positionMs: Long,
    val reason: String,
)
