package au.com.shiftyjelly.pocketcasts.repositories.ads.jev

import com.squareup.moshi.Moshi
import java.util.Optional
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JevClientTest {
    private val moshi = Moshi.Builder().build()

    private fun client(key: String?) = JevClient(
        okHttpClient = OkHttpClient(),
        apiKey = if (key == null) Optional.empty<String>() else Optional.of(key),
        moshi = moshi,
    )

    @Test
    fun `request carries the model, one question per sentence, the criteria and the auth header`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(RESPONSE_WITH_SECOND_ANSWER))
            server.start()
            val client = client(KEY).apply { endpoint = server.url("/api/v1/systemone").toString() }

            val result = client.classify(
                model = JevAdModel.SPAN_02,
                podcast = "Technology Today",
                sentences = listOf(JevSentence(1, "First sentence."), JevSentence(2, "Second sentence.")),
            )

            val scored = result as JevResponse.Scored
            assertEquals("respan/span-02-20261015", scored.responseModel)
            assertEquals(0.9969, scored.sentences.getValue(1).advertisementProbability!!, 0.0001)
            assertEquals("advertisement", scored.sentences.getValue(1).label)
            assertEquals("content", scored.sentences.getValue(2).label)
            assertEquals(0.00000132, scored.usageCostUsd!!, 1e-12)

            val recorded = server.takeRequest()
            assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
            assertEquals("POST", recorded.method)
            assertEquals("/api/v1/systemone", recorded.path)
            val raw = recorded.body.readUtf8()
            assertTrue(raw, raw.contains("\"model\":\"respan/span-02\""))
            for (criterion in JevPrompt.CRITERIA.values) {
                assertTrue(raw, raw.contains("\"$criterion\""))
            }
            val request = moshi.adapter(JevRequest::class.java).fromJson(raw)!!
            assertEquals("respan/span-02", request.model)
            assertEquals("Technology Today", request.state.podcast)
            assertEquals(listOf(1, 2), request.state.sentences.map { it.id })
            assertEquals(listOf("First sentence.", "Second sentence."), request.state.sentences.map { it.text })
            assertEquals(setOf("sentence_1", "sentence_2"), request.questions.keys)
            val question = request.questions.getValue("sentence_1")
            assertEquals("choice", question.type)
            assertEquals(JevPrompt.CRITERIA, question.criteria)
            assertEquals(
                "Classify only the sentence with id 1. Judge that sentence on its own wording, " +
                    "using the rest of the transcript only as context. Ignore every other sentence: " +
                    "they are separate questions.",
                question.instructions,
            )
            assertEquals(
                "Classify only the sentence with id 2. Judge that sentence on its own wording, " +
                    "using the rest of the transcript only as context. Ignore every other sentence: " +
                    "they are separate questions.",
                request.questions.getValue("sentence_2").instructions,
            )
        }
    }

    @Test
    fun `a missing answer is left out instead of failing the whole request`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(RESPONSE_WITHOUT_SECOND_ANSWER))
            server.start()
            val client = client(KEY).apply { endpoint = server.url("/").toString() }

            val result = client.classify(
                model = JevAdModel.JEV_1_14,
                podcast = "Technology Today",
                sentences = listOf(JevSentence(1, "First sentence."), JevSentence(2, "Second sentence.")),
            )

            val scored = result as JevResponse.Scored
            assertEquals(setOf(1), scored.sentences.keys)
            assertNull(scored.sentences[2])
            assertEquals("typesafe/jev-1.14-20261014", scored.responseModel)
        }
    }

    @Test
    fun `a probability that is absent falls back to the answer choice`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(RESPONSE_WITHOUT_PROBABILITIES))
            server.start()
            val client = client(KEY).apply { endpoint = server.url("/").toString() }

            val result = client.classify(
                model = JevAdModel.SPAN_02,
                podcast = "Technology Today",
                sentences = listOf(JevSentence(1, "First sentence.")),
            )

            val scored = result as JevResponse.Scored
            assertEquals(1.0, scored.sentences.getValue(1).advertisementProbability!!, 0.0)
        }
    }

    @Test
    fun `an HTTP error is reported as a failure instead of throwing`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"boom"}}"""))
            server.start()
            val client = client(KEY).apply { endpoint = server.url("/").toString() }

            val result = client.classify(JevAdModel.SPAN_02, "Technology Today", listOf(JevSentence(1, "Hi.")))

            assertTrue(result.toString(), result is JevResponse.Failed)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `malformed JSON is reported as a failure instead of throwing`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>not json</html>"))
            server.start()
            val client = client(KEY).apply { endpoint = server.url("/").toString() }

            val result = client.classify(JevAdModel.SPAN_02, "Technology Today", listOf(JevSentence(1, "Hi.")))

            assertTrue(result.toString(), result is JevResponse.Failed)
        }
    }

    @Test
    fun `a blank key makes the client unavailable without a request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val client = client("  ").apply { endpoint = server.url("/").toString() }

            val result = client.classify(JevAdModel.SPAN_02, "Technology Today", listOf(JevSentence(1, "Hi.")))

            assertTrue(result.toString(), result is JevResponse.Unavailable)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `an absent key binding makes the client unavailable without a request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val client = client(null).apply { endpoint = server.url("/").toString() }

            val result = client.classify(JevAdModel.SPAN_02, "Technology Today", listOf(JevSentence(1, "Hi.")))

            assertTrue(result.toString(), result is JevResponse.Unavailable)
            assertEquals(0, server.requestCount)
        }
    }

    private companion object {
        const val KEY = "test-key"

        // Shape copied from dev/adaware/next/jev/OPENROUTER.md.
        const val RESPONSE_WITH_SECOND_ANSWER = """
{
  "model": "respan/span-02-20261015",
  "answers": {
    "sentence_1": {
      "type": "choice",
      "choice": "advertisement",
      "probabilities": {"advertisement": 0.9969, "content": 0.0031},
      "confidence": 0.9937
    },
    "sentence_2": {
      "type": "choice",
      "choice": "content",
      "probabilities": {"advertisement": 0.0112, "content": 0.9888},
      "confidence": 0.9888
    }
  },
  "usage": {
    "input_tokens": 132,
    "output_tokens": 0,
    "cost": 0.00000132,
    "is_byok": false,
    "cost_details": {"upstream_inference_cost": 0.00000132}
  },
  "id": "gen-dec-1794640610-scbAzqDlQoQHthhxiEXw",
  "provider": "Respan"
}
"""

        const val RESPONSE_WITHOUT_SECOND_ANSWER = """
{
  "model": "typesafe/jev-1.14-20261014",
  "answers": {
    "sentence_1": {
      "type": "choice",
      "choice": "content",
      "probabilities": {"advertisement": 0.0042, "content": 0.9958}
    }
  },
  "usage": {"cost": 0.000134}
}
"""

        const val RESPONSE_WITHOUT_PROBABILITIES = """
{
  "model": "respan/span-02-20261015",
  "answers": {
    "sentence_1": {"type": "choice", "choice": "advertisement"}
  },
  "usage": {"cost": 0.000118}
}
"""
    }
}
