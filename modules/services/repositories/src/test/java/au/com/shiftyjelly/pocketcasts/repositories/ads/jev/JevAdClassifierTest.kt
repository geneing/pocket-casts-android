package au.com.shiftyjelly.pocketcasts.repositories.ads.jev

import au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptCue
import com.squareup.moshi.Moshi
import java.util.Optional
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
    fun `a leading cue that still leans break is pulled into the pod`() {
        val cues = listOf(cue(0, 10), cue(10, 20), cue(20, 30), cue(30, 40))

        val pods = buildPods(
            cues = cues,
            pAd = listOf(0.3, 0.45, 0.9, 0.05),
            pContent = listOf(0.6, 0.4, 0.05, 0.9),
        )

        assertEquals(1, pods.size)
        assertEquals(10_000L, pods[0].startMs)
        assertEquals(30_000L, pods[0].endMs)
        assertEquals(1, pods[0].firstCue)
        assertEquals(2, pods[0].lastCue)
    }

    @Test
    fun `a trailing cue that still leans break is pulled into the pod`() {
        val cues = listOf(cue(0, 10), cue(10, 20), cue(20, 30), cue(30, 40))

        val pods = buildPods(
            cues = cues,
            pAd = listOf(0.05, 0.9, 0.45, 0.3),
            pContent = listOf(0.9, 0.05, 0.4, 0.6),
        )

        assertEquals(1, pods.size)
        assertEquals(10_000L, pods[0].startMs)
        assertEquals(30_000L, pods[0].endMs)
    }

    @Test
    fun `a cue the show explains better stops the extension`() {
        val cues = listOf(cue(0, 10), cue(10, 20), cue(20, 30))

        val pods = buildPods(
            cues = cues,
            pAd = listOf(0.35, 0.9, 0.35),
            pContent = listOf(0.5, 0.05, 0.5),
        )

        assertEquals(1, pods.size)
        assertEquals(10_000L, pods[0].startMs)
        assertEquals(20_000L, pods[0].endMs)
        assertEquals(1, pods[0].firstCue)
        assertEquals(1, pods[0].lastCue)
    }

    @Test
    fun `the edge grows no further than the guard distance`() {
        val cues = listOf(cue(0, 20), cue(20, 40), cue(40, 60), cue(60, 80))

        val pods = buildPods(
            cues = cues,
            pAd = listOf(0.45, 0.45, 0.9, 0.0),
            pContent = listOf(0.4, 0.4, 0.05, 0.95),
            maxEdgeMs = 25_000L,
        )

        assertEquals(1, pods.size)
        assertEquals(20_000L, pods[0].startMs)
        assertEquals(60_000L, pods[0].endMs)
    }

    @Test
    fun `edges that grow into each other become one pod`() {
        val cues = listOf(cue(0, 20), cue(20, 40), cue(40, 60), cue(60, 80))

        val pods = buildPods(
            cues = cues,
            pAd = listOf(0.9, 0.45, 0.45, 0.9),
            pContent = listOf(0.05, 0.4, 0.4, 0.05),
        )

        assertEquals(1, pods.size)
        assertEquals(0L, pods[0].startMs)
        assertEquals(80_000L, pods[0].endMs)
    }

    @Test
    fun `without content probabilities the pods are the seeds alone`() {
        val cues = listOf(cue(0, 10), cue(10, 20), cue(20, 30))

        val pods = buildPods(cues, listOf(0.45, 0.9, 0.45))

        assertEquals(1, pods.size)
        assertEquals(10_000L, pods[0].startMs)
        assertEquals(20_000L, pods[0].endMs)
    }

    @Test
    fun `the defaults match the labelled-corpus evaluation`() {
        assertEquals(0.5f, AD_PROBABILITY_THRESHOLD)
        assertEquals(1, MAX_GAP_CUES)
        assertEquals(8_000L, MIN_POD_MS)
        assertEquals(30_000L, MAX_EDGE_MS)
        assertEquals(20, JevAdClassifier.WINDOW_CUES)
    }

    @Test
    fun `a complete answer is scored`() = runTest {
        withClassifier(ANSWER_BOTH) { classifier ->
            val result = classifier.classify(listOf(cue(0, 10), cue(10, 20)), SHOW, JevAdModel.SPAN_02)

            val scored = result as JevClassification.Scored
            assertEquals(listOf(0.9, 0.2), scored.pAd)
            assertEquals(listOf(0.1, 0.8), scored.pContent)
            assertEquals("respan/span-02-20261015", scored.responseModel)
        }
    }

    @Test
    fun `an unanswered cue fails instead of producing a partial analysis`() = runTest {
        withClassifier(ANSWER_FIRST_ONLY) { classifier ->
            val result = classifier.classify(listOf(cue(0, 10), cue(10, 20)), SHOW, JevAdModel.SPAN_02)

            // Partial scoring would be persisted as complete and never retried, so it must fail
            // and let the audio analyser cover the episode.
            val failed = result as JevClassification.Failed
            assertTrue(failed.reason, failed.reason.contains("1 of 2 cues"))
        }
    }

    @Test
    fun `a transition cue is classified as part of the ad break`() = runTest {
        withClassifier(ANSWER_WITH_TRANSITION) { classifier ->
            val result = classifier.classify(
                listOf(cue(0, 10), cue(10, 20), cue(20, 30)),
                SHOW,
                JevAdModel.SPAN_02,
            )

            val scored = result as JevClassification.Scored
            assertEquals(listOf(0.9, 0.8, 0.1), scored.pAd)
        }
    }

    @Test
    fun `a transition verdict without probabilities still scores as an ad break`() = runTest {
        withClassifier(ANSWER_TRANSITION_NO_PROBABILITIES) { classifier ->
            val result = classifier.classify(listOf(cue(0, 10)), SHOW, JevAdModel.SPAN_02)

            val scored = result as JevClassification.Scored
            assertEquals(listOf(1.0), scored.pAd)
        }
    }

    private suspend fun withClassifier(
        responseBody: String,
        block: suspend (JevAdClassifier) -> Unit,
    ) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(responseBody))
            server.start()
            val client = JevClient(
                okHttpClient = OkHttpClient(),
                apiKey = Optional.of(KEY),
                moshi = Moshi.Builder().build(),
            ).apply { endpoint = server.url("/api/v1/systemone").toString() }
            block(JevAdClassifier(client))
        }
    }

    private companion object {
        const val SHOW = "Technology Today"
        const val KEY = "test-key"
        const val ANSWER_FIRST_ONLY =
            """{"model":"respan/span-02-20261015","answers":{"sentence_1":{"type":"choice",""" +
                """"choice":"advertisement","probabilities":{"advertisement":0.9,"content":0.1}}}}"""
        const val ANSWER_BOTH =
            """{"model":"respan/span-02-20261015","answers":{""" +
                """"sentence_1":{"type":"choice","choice":"advertisement",""" +
                """"probabilities":{"advertisement":0.9,"content":0.1}},""" +
                """"sentence_2":{"type":"choice","choice":"content",""" +
                """"probabilities":{"advertisement":0.2,"content":0.8}}}}"""
        const val ANSWER_WITH_TRANSITION =
            """{"model":"respan/span-02-20261015","answers":{""" +
                """"sentence_1":{"type":"choice","choice":"advertisement",""" +
                """"probabilities":{"advertisement":0.9,"content":0.05,"transition":0.05}},""" +
                """"sentence_2":{"type":"choice","choice":"transition",""" +
                """"probabilities":{"advertisement":0.1,"content":0.1,"transition":0.8}},""" +
                """"sentence_3":{"type":"choice","choice":"content",""" +
                """"probabilities":{"advertisement":0.1,"content":0.85,"transition":0.05}}}}"""
        const val ANSWER_TRANSITION_NO_PROBABILITIES =
            """{"model":"respan/span-02-20261015","answers":{""" +
                """"sentence_1":{"type":"choice","choice":"transition"}}}"""
    }
}
