package au.com.shiftyjelly.pocketcasts.models.to

/** One transcript cue with the classifier's probability that it is advertising. */
data class AdCueScore(
    val cueIndex: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val probability: Float,
)
