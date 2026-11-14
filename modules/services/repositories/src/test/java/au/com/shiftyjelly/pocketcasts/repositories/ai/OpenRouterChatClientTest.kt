package au.com.shiftyjelly.pocketcasts.repositories.ai

import com.squareup.moshi.Moshi
import java.util.Optional
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterChatClientTest {
    private val moshi = Moshi.Builder().build()

    private fun client(key: String?) = OpenRouterChatClient(
        okHttpClient = OkHttpClient(),
        apiKey = if (key == null) Optional.empty<String>() else Optional.of(key),
        moshi = moshi,
    )

    private fun OpenRouterChatClient.pointingAt(server: MockWebServer) = apply {
        endpoint = server.url("/api/v1/chat/completions").toString()
    }

    @Test
    fun `request carries the model, the transcript in the system message and the conversation`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(RESPONSE_WITH_REASONING))
            server.start()
            val client = client(KEY).pointingAt(server)

            val result = client.ask(
                question = "What did the host say about knots?",
                transcript = "We talk about knots today.",
                history = listOf(
                    AiChatMessage(AiChatMessage.Role.User, "Summarize the podcast"),
                    AiChatMessage(AiChatMessage.Role.Assistant, "A short episode about knots."),
                ),
            )

            // Only `content` is the answer; `reasoning` is the model's scratchpad.
            val answer = result as AiChatResult.Answer
            assertEquals("The host says a knot is a unit of speed.", answer.text)
            assertEquals(0.000328, answer.costUsd!!, 1e-9)

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/v1/chat/completions", recorded.path)
            assertEquals("Bearer $KEY", recorded.getHeader("Authorization"))
            assertTrue(recorded.getHeader("Content-Type")!!, recorded.getHeader("Content-Type")!!.startsWith("application/json"))
            val raw = recorded.body.readUtf8()
            assertTrue(raw, raw.contains("\"model\":\"${AiChatPrompt.MODEL}\""))
            assertTrue(raw, raw.contains("\"max_tokens\":${AiChatPrompt.MAX_TOKENS}"))

            val request = moshi.adapter(AiChatRequest::class.java).fromJson(raw)!!
            assertEquals(AiChatPrompt.MODEL, request.model)
            assertEquals(AiChatPrompt.MAX_TOKENS, request.maxTokens)
            assertEquals(listOf("system", "user", "assistant", "user"), request.messages.map { it.role })

            val system = request.messages[0].content
            assertTrue(system, system.startsWith(AiChatPrompt.SYSTEM))
            assertTrue(system, system.contains("We talk about knots today."))
            assertEquals("Summarize the podcast", request.messages[1].content)
            assertEquals("A short episode about knots.", request.messages[2].content)
            assertEquals("What did the host say about knots?", request.messages[3].content)
        }
    }

    @Test
    fun `a first question is sent without any history`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(RESPONSE_WITH_REASONING))
            server.start()
            val client = client(KEY).pointingAt(server)

            client.ask(question = "Summarize the podcast", transcript = "Knots.")

            val raw = server.takeRequest().body.readUtf8()
            val request = moshi.adapter(AiChatRequest::class.java).fromJson(raw)!!
            assertEquals(listOf("system", "user"), request.messages.map { it.role })
            assertEquals("Summarize the podcast", request.messages[1].content)
        }
    }

    @Test
    fun `an unauthorised response fails without leaking the key`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"error":{"message":"No auth credentials found","code":401}}"""),
            )
            server.start()
            val client = client(KEY).pointingAt(server)

            val result = client.ask("Summarize the podcast", "Knots.")

            val failed = result as AiChatResult.Failed
            assertEquals("HTTP 401", failed.reason)
            assertFalse(failed.reason, failed.reason.contains(KEY))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `a server error is reported as a failure instead of throwing`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"boom"}}"""))
            server.start()
            val client = client(KEY).pointingAt(server)

            val result = client.ask("Summarize the podcast", "Knots.")

            assertEquals("HTTP 500", (result as AiChatResult.Failed).reason)
        }
    }

    @Test
    fun `a response without message content is a failure`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(RESPONSE_WITHOUT_CONTENT))
            server.start()
            val client = client(KEY).pointingAt(server)

            val result = client.ask("Summarize the podcast", "Knots.")

            assertTrue(result.toString(), result is AiChatResult.Failed)
        }
    }

    @Test
    fun `an absent key makes the client unavailable without a request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val client = client(null).pointingAt(server)

            val result = client.ask("Summarize the podcast", "Knots.")

            assertEquals(AiChatResult.NoApiKey, result)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `a blank key makes the client unavailable without a request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val client = client("  ").pointingAt(server)

            val result = client.ask("Summarize the podcast", "Knots.")

            assertEquals(AiChatResult.NoApiKey, result)
            assertEquals(0, server.requestCount)
        }
    }

    private companion object {
        const val KEY = "sk-or-v1-test-key"

        // Shape measured against OpenRouter's chat completions endpoint.
        const val RESPONSE_WITH_REASONING = """
{
  "id": "gen-1762920000-pQ4rXk7TbY",
  "provider": "DeepSeek",
  "model": "deepseek/deepseek-v4.1-flash-1017",
  "object": "chat.completion",
  "created": 1762920000,
  "choices": [
    {
      "index": 0,
      "finish_reason": "stop",
      "message": {
        "role": "assistant",
        "content": "The host says a knot is a unit of speed.",
        "reasoning": "The transcript mentions knots while talking about sailing, so it is a unit of speed."
      }
    }
  ],
  "usage": {
    "prompt_tokens": 1298,
    "completion_tokens": 11,
    "total_tokens": 1309,
    "cost": 0.000328
  }
}
"""

        const val RESPONSE_WITHOUT_CONTENT = """
{
  "model": "deepseek/deepseek-v4.1-flash-1017",
  "choices": [
    {
      "index": 0,
      "finish_reason": "length",
      "message": {"role": "assistant", "content": "", "reasoning": "I was still thinking."}
    }
  ],
  "usage": {"prompt_tokens": 1298, "completion_tokens": 1024, "cost": 0.000512}
}
"""
    }
}
