package au.com.shiftyjelly.pocketcasts.repositories.ads.jev

import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JevAdClassifierTest {

    private fun cue(startSeconds: Long, endSeconds: Long) = TranscriptCue(
        startMs = startSeconds * 1_000,
        endMs = endSeconds * 1_000,
        text = "Cue $startSeconds-$endSeconds",
    )

    @Test
    fun `a single ad cue becomes one pod`() {
        val cues = listOf(cue(0, 10), cue(10, 20))

        val pods = buildPods(cues, listOf(0.9, 0.1))

        assertEquals(1, pods.size)
        assertEquals(0L, pods[0].startMs)
        assertEquals(10_000L, pods[0].endMs)
        assertEquals(0, pods[0].firstCue)
        assertEquals(0, pods[0].lastCue)
    }

    @Test
    fun `a one-cue gap is bridged and counts towards the mean`() {
        val cues = listOf(cue(0, 10), cue(10, 20), cue(20, 30), cue(30, 40))

        val pods = buildPods(cues, listOf(0.9, 0.1, 0.8, 0.0))

        assertEquals(1, pods.size)
        assertEquals(0L, pods[0].startMs)
        assertEquals(30_000L, pods[0].endMs)
        assertEquals(3, pods[0].lastCue - pods[0].firstCue + 1)
        assertEquals(0.6f, pods[0].meanAdvertisementProbability, 0.0001f)
    }

    @Test
    fun `a two-cue gap splits the run into two pods`() {
        val cues = listOf(cue(0, 10), cue(10, 20), cue(20, 30), cue(30, 40))

        val pods = buildPods(cues, listOf(0.9, 0.1, 0.2, 0.9))

        assertEquals(2, pods.size)
        assertEquals(0L, pods[0].startMs)
        assertEquals(10_000L, pods[0].endMs)
        assertEquals(30_000L, pods[1].startMs)
        assertEquals(40_000L, pods[1].endMs)
    }

    @Test
    fun `a pod shorter than eight seconds is dropped`() {
        val cues = listOf(cue(0, 5), cue(5, 20))

        val pods = buildPods(cues, listOf(0.9, 0.0))

        assertTrue(pods.isEmpty())
    }

    @Test
    fun `the mean ignores cues without a probability`() {
        val cues = listOf(cue(0, 10), cue(10, 20), cue(20, 30))

        val pods = buildPods(cues, listOf(0.6, null, 0.9))

        assertEquals(1, pods.size)
        assertEquals(0.75f, pods[0].meanAdvertisementProbability, 0.0001f)
    }

    @Test
    fun `a cue at exactly the threshold is an ad`() {
        val cues = listOf(cue(0, 10))

        val pods = buildPods(cues, listOf(AD_PROBABILITY_THRESHOLD.toDouble()))

        assertEquals(1, pods.size)
        assertEquals(AD_PROBABILITY_THRESHOLD, pods[0].meanAdvertisementProbability, 0.0001f)
    }

    @Test
    fun `the defaults match the labelled-corpus evaluation`() {
        assertEquals(0.5f, AD_PROBABILITY_THRESHOLD)
        assertEquals(1, MAX_GAP_CUES)
        assertEquals(8_000L, MIN_POD_MS)
        assertEquals(20, JevAdClassifier.WINDOW_CUES)
    }
}
