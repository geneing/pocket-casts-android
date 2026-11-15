package au.com.shiftyjelly.pocketcasts.player.view.transcript

import au.com.shiftyjelly.pocketcasts.models.to.AdCueScore
import au.com.shiftyjelly.pocketcasts.models.to.AdSegment
import au.com.shiftyjelly.pocketcasts.models.to.TranscriptEntry
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptAdSentencesTest {
    @Test
    fun `sentence fully inside a segment is an ad sentence`() {
        val sentence = sentence(10, 12)

        assertTrue(isAdSentence(sentence, listOf(segment(8, 14))))
        assertTrue(isAdSentence(sentence, listOf(segment(10, 12))))
    }

    @Test
    fun `sentence overlapping a segment by half its duration is an ad sentence`() {
        assertTrue(isAdSentence(sentence(10, 12), listOf(segment(11, 13))))
        assertTrue(isAdSentence(sentence(10, 12), listOf(segment(9, 11))))
    }

    @Test
    fun `sentence overlapping a segment by less than half is not an ad sentence`() {
        assertFalse(isAdSentence(sentence(10, 14), listOf(segment(13, 15))))
    }

    @Test
    fun `sentence touching a segment only at the boundary is not an ad sentence`() {
        assertFalse(isAdSentence(sentence(10, 12), listOf(segment(12, 14))))
        assertFalse(isAdSentence(sentence(10, 12), listOf(segment(8, 10))))
    }

    @Test
    fun `long sentence spanning a whole short segment is not an ad sentence`() {
        assertFalse(isAdSentence(sentence(10, 14), listOf(segment(10, 11))))
    }

    @Test
    fun `deselected segment is ignored`() {
        val sentence = sentence(10, 12)

        assertFalse(isAdSentence(sentence, listOf(segment(8, 14, selected = false))))
        assertTrue(
            isAdSentence(
                sentence,
                listOf(segment(8, 14, selected = false), segment(10, 12)),
            ),
        )
    }

    @Test
    fun `no segments means no ad sentences`() {
        assertFalse(isAdSentence(sentence(10, 12), emptyList()))
    }

    @Test
    fun `adjacent segments that together cover most of the sentence are ad copy`() {
        assertTrue(isAdSentence(sentence(10, 12), listOf(segment(10, 11), segment(11, 12))))
    }

    @Test
    fun `sentence without an end time falls back to its start time`() {
        val inside = TranscriptEntry.Text(value = "Sentence", startTimeMs = 10_000L)
        val outside = TranscriptEntry.Text(value = "Sentence", startTimeMs = 20_000L)

        assertTrue(isAdSentence(inside, listOf(segment(8, 14))))
        assertFalse(isAdSentence(outside, listOf(segment(8, 14))))
    }

    @Test
    fun `the ad segment supplies its probability for the sentence`() {
        val ad = segment(8, 14).copy(confidence = 0.75f)

        assertEquals(0.75f, adSegmentFor(sentence(10, 12), listOf(ad))?.confidence)
    }

    @Test
    fun `the segment covering the most of the sentence supplies the probability`() {
        val covering = segment(8, 14).copy(confidence = 0.9f)
        val brushing = segment(10, 10).copy(confidence = 0.2f)

        assertEquals(0.9f, adSegmentFor(sentence(10, 12), listOf(brushing, covering))?.confidence)
        assertEquals(0.9f, adSegmentFor(sentence(10, 12), listOf(covering, brushing))?.confidence)
    }

    @Test
    fun `a deselected or uncovered sentence has no probability`() {
        assertNull(adSegmentFor(sentence(10, 12), listOf(segment(8, 14, selected = false))))
        assertNull(adSegmentFor(sentence(10, 12), emptyList()))
        assertNull(adSegmentFor(sentence(10, 12), listOf(segment(14, 16))))
    }

    @Test
    fun `a cue score is matched by the sentence start`() {
        val scores = listOf(score(9, 4, 0.04f), score(10, 2, 0.79f))

        assertEquals(0.79f, cueProbabilityFor(sentence(10, 12), scores))
        assertEquals(0.04f, cueProbabilityFor(sentence(9, 10), scores))
        assertNull(cueProbabilityFor(sentence(30, 35), scores))
    }

    @Test
    fun `a cue score covers a sentence whose start shifted`() {
        val scores = listOf(AdCueScore(cueIndex = 0, startTimeMs = 10_000, endTimeMs = 13_000, probability = 0.2f))

        assertEquals(0.2f, cueProbabilityFor(sentence(11, 12), scores))
    }
}

private fun score(startSeconds: Int, endSeconds: Int, probability: Float) = AdCueScore(
    cueIndex = 0,
    startTimeMs = startSeconds * 1000L,
    endTimeMs = endSeconds * 1000L,
    probability = probability,
)

private fun sentence(startSeconds: Int, endSeconds: Int) = TranscriptEntry.Text(
    value = "Sentence",
    startTimeMs = startSeconds * 1000L,
    endTimeMs = endSeconds * 1000L,
)

private fun segment(startSeconds: Int, endSeconds: Int, selected: Boolean = true) = AdSegment(
    index = 0,
    startTime = startSeconds.seconds,
    endTime = endSeconds.seconds,
    selected = selected,
    isAligned = true,
)
