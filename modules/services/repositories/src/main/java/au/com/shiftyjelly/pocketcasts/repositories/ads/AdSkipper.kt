package au.com.shiftyjelly.pocketcasts.repositories.ads

import au.com.shiftyjelly.pocketcasts.models.to.AdSegment

/**
 * Decides whether playback should jump over an advertising segment. Kept free of Android and player
 * dependencies so the rules can be unit tested.
 */
object AdSkipper {
    /** A segment must have at least this much left, otherwise skipping just adds a seek. */
    const val MIN_REMAINING_MS = 2_000L

    /** Never skip to a point this close to the end, the episode would appear finished. */
    const val END_MARGIN_MS = 5_000L

    /** A normal playback tick is around a second; a bigger jump means the listener moved the position. */
    const val MAX_STEP_MS = 3_000L

    fun nextSegmentToSkip(
        positionMs: Int,
        previousPositionMs: Int?,
        durationMs: Int,
        segments: List<AdSegment>,
    ): AdSegment? {
        if (segments.isEmpty()) {
            return null
        }
        if (previousPositionMs != null && (positionMs - previousPositionMs) !in 0..MAX_STEP_MS) {
            return null
        }
        return segments.firstOrNull { segment -> segment.shouldSkipAt(positionMs, durationMs) }
    }

    fun activeSegment(positionMs: Int, segments: List<AdSegment>): AdSegment? {
        return segments.firstOrNull { segment ->
            positionMs >= segment.startTime.inWholeMilliseconds &&
                positionMs < segment.endTime.inWholeMilliseconds
        }
    }

    fun skipTargetMs(segment: AdSegment): Int = segment.endTime.inWholeMilliseconds.toInt()

    private fun AdSegment.shouldSkipAt(positionMs: Int, durationMs: Int): Boolean {
        val start = startTime.inWholeMilliseconds
        val end = endTime.inWholeMilliseconds
        return isAutoSkippable &&
            isAligned &&
            positionMs >= start &&
            positionMs < end &&
            end - positionMs >= MIN_REMAINING_MS &&
            (durationMs <= 0 || end <= durationMs - END_MARGIN_MS)
    }
}
