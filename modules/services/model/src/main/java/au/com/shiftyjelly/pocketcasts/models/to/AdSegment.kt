package au.com.shiftyjelly.pocketcasts.models.to

import kotlin.time.Duration

/**
 * An advertising, sponsor, or self-promotional segment of an episode, positioned in the timeline of
 * the audio that is currently playing.
 */
data class AdSegment(
    val index: Int,
    val startTime: Duration,
    val endTime: Duration,
    val kind: AdSegmentKind = AdSegmentKind.Ad,
    val origin: AdSegmentOrigin = AdSegmentOrigin.Unknown,
    val confidence: Float = DEFAULT_CONFIDENCE,
    val title: String? = null,
    val selected: Boolean = true,
    // True when the timestamps match the audio being played. On-device analysis always is.
    val isAligned: Boolean = false,
    // Reference-timeline start, kept for segments that came from a server timeline.
    val referenceStartTime: Duration? = null,
) {
    val duration: Duration
        get() = endTime - startTime

    val isConfident: Boolean
        get() = confidence >= MIN_AUTO_SKIP_CONFIDENCE

    /** Segments that may be skipped without asking the listener first. */
    val isAutoSkippable: Boolean
        get() = selected && isConfident

    operator fun contains(duration: Duration): Boolean {
        return duration >= startTime && duration < endTime
    }

    companion object {
        const val MIN_AUTO_SKIP_CONFIDENCE = 0.6f
        const val DEFAULT_CONFIDENCE = 1f
    }
}
