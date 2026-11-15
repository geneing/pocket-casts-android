package au.com.shiftyjelly.pocketcasts.player.view.transcript

import au.com.shiftyjelly.pocketcasts.models.to.AdSegment
import au.com.shiftyjelly.pocketcasts.models.to.TranscriptEntry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The ad segment that marks [sentence] as ad copy, or null when no selected segment does.
 *
 * Ad segment times are already in the timeline of the audio being played (on-device analysis always
 * sets [AdSegment.isAligned]) and transcript entries use that same timeline, so the two are compared
 * directly without shifting either of them.
 *
 * A sentence is an ad when the selected segments cover at least half of its duration. Being duration
 * aware, the rule marks a short sentence that sits inside an ad, and leaves a long sentence that only
 * brushes the start or the end of an ad alone, so the italics cover what the listener hears as ad
 * copy rather than every sentence that happens to touch it. Deselected segments are ignored because
 * the listener has opted out of them. When several segments overlap, the one covering the most of
 * the sentence supplies the probability shown next to it.
 */
internal fun adSegmentFor(sentence: TranscriptEntry.Text, segments: List<AdSegment>): AdSegment? {
    val selected = segments.filter { it.selected }
    if (selected.isEmpty()) {
        return null
    }
    val start = sentence.startTimeMs.milliseconds
    val end = sentence.endTimeMs.milliseconds
    val duration = end - start
    if (duration <= Duration.ZERO) {
        // An entry without a usable end time cannot be measured, so its start time stands in for it.
        return selected.firstOrNull { start in it }
    }
    var best: AdSegment? = null
    var bestOverlap = Duration.ZERO
    for (segment in selected) {
        val overlap = minOf(end, segment.endTime) - maxOf(start, segment.startTime)
        if (overlap > bestOverlap) {
            best = segment
            bestOverlap = overlap
        }
    }
    return best?.takeIf { bestOverlap * 2 >= duration }
}

/** Whether [sentence] is ad copy, using the same coverage rule as [adSegmentFor]. */
internal fun isAdSentence(sentence: TranscriptEntry.Text, segments: List<AdSegment>): Boolean {
    return adSegmentFor(sentence, segments) != null
}
