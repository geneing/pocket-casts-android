package au.com.shiftyjelly.pocketcasts.repositories.ads

import au.com.shiftyjelly.pocketcasts.models.to.AdSegment
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdSkipperTest {
    private val episodeDurationMs = 60.minutes.inWholeMilliseconds.toInt()

    @Test
    fun `skips a segment the listener has played into`() {
        val segment = adSegment(startSeconds = 300, endSeconds = 360)

        val skip = AdSkipper.nextSegmentToSkip(
            positionMs = 310_000,
            previousPositionMs = 309_000,
            durationMs = episodeDurationMs,
            segments = listOf(segment),
        )

        assertEquals(segment, skip)
        assertEquals(360_000, AdSkipper.skipTargetMs(segment))
    }

    @Test
    fun `does not skip before the segment starts`() {
        val skip = AdSkipper.nextSegmentToSkip(
            positionMs = 299_000,
            previousPositionMs = 298_000,
            durationMs = episodeDurationMs,
            segments = listOf(adSegment(startSeconds = 300, endSeconds = 360)),
        )

        assertNull(skip)
    }

    @Test
    fun `does not skip when there is almost nothing left`() {
        val skip = AdSkipper.nextSegmentToSkip(
            positionMs = 359_000,
            previousPositionMs = 358_000,
            durationMs = episodeDurationMs,
            segments = listOf(adSegment(startSeconds = 300, endSeconds = 360)),
        )

        assertNull(skip)
    }

    @Test
    fun `does not skip a segment that starts at the very end of the episode`() {
        val skip = AdSkipper.nextSegmentToSkip(
            positionMs = 3_597_000,
            previousPositionMs = 3_596_000,
            durationMs = episodeDurationMs,
            segments = listOf(adSegment(startSeconds = 3590, endSeconds = 3600)),
        )

        assertNull(skip)
    }

    @Test
    fun `does not skip after the listener jumped in the timeline`() {
        val skip = AdSkipper.nextSegmentToSkip(
            positionMs = 310_000,
            previousPositionMs = 200_000,
            durationMs = episodeDurationMs,
            segments = listOf(adSegment(startSeconds = 300, endSeconds = 360)),
        )

        assertNull(skip)
    }

    @Test
    fun `does not skip segments that are not aligned to the audio`() {
        val skip = AdSkipper.nextSegmentToSkip(
            positionMs = 310_000,
            previousPositionMs = 309_000,
            durationMs = episodeDurationMs,
            segments = listOf(adSegment(startSeconds = 300, endSeconds = 360, isAligned = false)),
        )

        assertNull(skip)
    }

    @Test
    fun `does not skip segments the listener turned off`() {
        val skip = AdSkipper.nextSegmentToSkip(
            positionMs = 310_000,
            previousPositionMs = 309_000,
            durationMs = episodeDurationMs,
            segments = listOf(adSegment(startSeconds = 300, endSeconds = 360, selected = false)),
        )

        assertNull(skip)
    }

    @Test
    fun `does not skip low confidence guesses`() {
        val skip = AdSkipper.nextSegmentToSkip(
            positionMs = 310_000,
            previousPositionMs = 309_000,
            durationMs = episodeDurationMs,
            segments = listOf(adSegment(startSeconds = 300, endSeconds = 360, confidence = 0.3f)),
        )

        assertNull(skip)
    }

    @Test
    fun `manual skip finds the active segment even when it is unaligned`() {
        val segment = adSegment(startSeconds = 300, endSeconds = 360, isAligned = false)

        assertEquals(segment, AdSkipper.activeSegment(310_000, listOf(segment)))
        assertNull(AdSkipper.activeSegment(299_000, listOf(segment)))
    }

    private fun adSegment(
        startSeconds: Int,
        endSeconds: Int,
        isAligned: Boolean = true,
        selected: Boolean = true,
        confidence: Float = 0.9f,
    ) = AdSegment(
        index = 0,
        startTime = startSeconds.seconds,
        endTime = endSeconds.seconds,
        confidence = confidence,
        selected = selected,
        isAligned = isAligned,
        referenceStartTime = startSeconds.milliseconds,
    )
}
