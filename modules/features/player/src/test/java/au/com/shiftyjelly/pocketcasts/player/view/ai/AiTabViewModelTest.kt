package au.com.shiftyjelly.pocketcasts.player.view.ai

import au.com.shiftyjelly.pocketcasts.models.to.Transcript
import au.com.shiftyjelly.pocketcasts.models.to.TranscriptEntry
import au.com.shiftyjelly.pocketcasts.models.to.TranscriptType
import au.com.shiftyjelly.pocketcasts.repositories.ai.AiChatClient
import au.com.shiftyjelly.pocketcasts.repositories.ai.AiChatMessage
import au.com.shiftyjelly.pocketcasts.repositories.ai.AiChatResult
import au.com.shiftyjelly.pocketcasts.repositories.transcript.TranscriptManager
import au.com.shiftyjelly.pocketcasts.sharedtest.MainCoroutineRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class AiTabViewModelTest {
    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private val transcriptManager = mock<TranscriptManager>()

    private fun viewModel(
        client: FakeAiChatClient = FakeAiChatClient(),
    ) = AiTabViewModel(chatClient = client, transcriptManager = transcriptManager)

    @Test
    fun `a first question is answered from the episode transcript`() = runTest {
        whenever(transcriptManager.loadTranscript(EPISODE)).thenReturn(transcript("One.", "Two."))
        val client = FakeAiChatClient()
        val viewModel = viewModel(client).apply { onEpisodeChanged(EPISODE) }

        assertEquals(TranscriptAvailability.Available, viewModel.state.value.transcript)
        assertNull(viewModel.state.value.input)

        viewModel.send("What is a knot?")

        val state = viewModel.state.value
        assertEquals(listOf(AiChatMessage.Role.User, AiChatMessage.Role.Assistant), state.messages.map { it.role })
        assertEquals("What is a knot?", state.messages[0].content)
        assertEquals("An answer.", state.messages[1].content)
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals("", state.input)
        assertEquals("One.\nTwo.", client.calls.single().transcript)
        assertEquals(emptyList<AiChatMessage>(), client.calls.single().history)
    }

    @Test
    fun `a follow-up question carries the earlier turns`() = runTest {
        whenever(transcriptManager.loadTranscript(EPISODE)).thenReturn(transcript("One."))
        val client = FakeAiChatClient()
        val viewModel = viewModel(client).apply { onEpisodeChanged(EPISODE) }

        viewModel.send("First?")
        viewModel.send("Second?")

        val followUp = client.calls[1]
        assertEquals("Second?", followUp.question)
        assertEquals(
            listOf(
                AiChatMessage(AiChatMessage.Role.User, "First?"),
                AiChatMessage(AiChatMessage.Role.Assistant, "An answer."),
            ),
            followUp.history,
        )
        assertEquals(4, viewModel.state.value.messages.size)
    }

    @Test
    fun `the loading flag is set while the answer is on its way`() = runTest {
        whenever(transcriptManager.loadTranscript(EPISODE)).thenReturn(transcript("One."))
        val gate = CompletableDeferred<Unit>()
        val client = FakeAiChatClient(gate = gate)
        val viewModel = viewModel(client).apply { onEpisodeChanged(EPISODE) }

        viewModel.send("Summarize the podcast")

        assertTrue(viewModel.state.value.isLoading)
        assertFalse(viewModel.state.value.canSend)
        assertEquals(1, viewModel.state.value.messages.size)

        // A second question while the first is unanswered is ignored.
        viewModel.send("And then?")
        assertEquals(1, client.calls.size)

        gate.complete(Unit)

        assertFalse(viewModel.state.value.isLoading)
        assertTrue(viewModel.state.value.canSend)
        assertEquals(2, viewModel.state.value.messages.size)
    }

    @Test
    fun `a failed request shows an inline error and keeps the question`() = runTest {
        whenever(transcriptManager.loadTranscript(EPISODE)).thenReturn(transcript("One."))
        val client = FakeAiChatClient(result = AiChatResult.Failed("HTTP 500"))
        val viewModel = viewModel(client).apply { onEpisodeChanged(EPISODE) }

        viewModel.send("Summarize the podcast")

        val state = viewModel.state.value
        assertEquals(AiTabError.RequestFailed, state.error)
        assertEquals(listOf(AiChatMessage.Role.User), state.messages.map { it.role })
        assertFalse(state.isLoading)
        assertTrue(state.canSend)
    }

    @Test
    fun `a build without a key shows the unavailable error`() = runTest {
        whenever(transcriptManager.loadTranscript(EPISODE)).thenReturn(transcript("One."))
        val client = FakeAiChatClient(result = AiChatResult.NoApiKey)
        val viewModel = viewModel(client).apply { onEpisodeChanged(EPISODE) }

        viewModel.send("Summarize the podcast")

        assertEquals(AiTabError.Unavailable, viewModel.state.value.error)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun `a missing transcript disables sending`() = runTest {
        val client = FakeAiChatClient()
        val viewModel = viewModel(client).apply { onEpisodeChanged(EPISODE) }

        assertEquals(TranscriptAvailability.Missing, viewModel.state.value.transcript)
        assertFalse(viewModel.state.value.canSend)

        viewModel.send("Summarize the podcast")

        assertTrue(client.calls.isEmpty())
        assertTrue(viewModel.state.value.messages.isEmpty())
    }

    @Test
    fun `a web transcript is treated as missing`() = runTest {
        whenever(transcriptManager.loadTranscript(EPISODE)).thenReturn(
            Transcript.Web(
                url = "https://example.com/episode.html",
                isGenerated = false,
                episodeUuid = EPISODE,
                podcastUuid = PODCAST,
            ),
        )
        val viewModel = viewModel().apply { onEpisodeChanged(EPISODE) }

        assertEquals(TranscriptAvailability.Missing, viewModel.state.value.transcript)
        assertFalse(viewModel.state.value.canSend)
    }

    @Test
    fun `switching episodes starts a fresh conversation`() = runTest {
        whenever(transcriptManager.loadTranscript(EPISODE)).thenReturn(transcript("One."))
        whenever(transcriptManager.loadTranscript(OTHER_EPISODE)).thenReturn(transcript("Other."))
        val viewModel = viewModel().apply { onEpisodeChanged(EPISODE) }
        viewModel.send("Summarize the podcast")
        assertEquals(2, viewModel.state.value.messages.size)

        viewModel.onEpisodeChanged(OTHER_EPISODE)

        val state = viewModel.state.value
        assertTrue(state.messages.isEmpty())
        assertNull(state.input)
        assertEquals(TranscriptAvailability.Available, state.transcript)
    }

    @Test
    fun `a blank question is ignored`() = runTest {
        whenever(transcriptManager.loadTranscript(EPISODE)).thenReturn(transcript("One."))
        val client = FakeAiChatClient()
        val viewModel = viewModel(client).apply { onEpisodeChanged(EPISODE) }

        viewModel.send("   ")

        assertTrue(client.calls.isEmpty())
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun `a long transcript is capped before it reaches the client`() = runTest {
        val longLine = "b".repeat(MAX_TRANSCRIPT_CHARS * 2)
        whenever(transcriptManager.loadTranscript(EPISODE)).thenReturn(transcript("start", longLine, "end"))
        val client = FakeAiChatClient()
        val viewModel = viewModel(client).apply { onEpisodeChanged(EPISODE) }

        viewModel.send("Summarize the podcast")

        val sent = client.calls.single().transcript
        assertTrue(sent.length <= MAX_TRANSCRIPT_CHARS)
        assertTrue(sent, sent.startsWith("start"))
        assertTrue(sent, sent.endsWith("end"))
        assertTrue(sent, sent.contains(TRANSCRIPT_TRUNCATION_MARKER))
    }

    @Test
    fun `capping keeps the head and the tail of the transcript`() {
        val text = "a".repeat(50) + "b".repeat(MAX_TRANSCRIPT_CHARS * 2) + "c".repeat(50)

        val capped = capTranscript(text)

        assertTrue(capped.length <= MAX_TRANSCRIPT_CHARS)
        assertTrue(capped, capped.startsWith("a".repeat(50)))
        assertTrue(capped, capped.endsWith("c".repeat(50)))
        assertTrue(capped, capped.contains(TRANSCRIPT_TRUNCATION_MARKER))
    }

    @Test
    fun `a transcript shorter than the cap is left alone`() {
        val text = "A short transcript."

        assertEquals(text, capTranscript(text))
    }

    private fun transcript(vararg lines: String) = Transcript.Text(
        entries = lines.map { TranscriptEntry.Text(it) },
        type = TranscriptType.Vtt,
        url = "https://example.com/transcript.vtt",
        isGenerated = false,
        episodeUuid = EPISODE,
        podcastUuid = PODCAST,
    )

    private class FakeAiChatClient(
        private val result: AiChatResult = AiChatResult.Answer("An answer.", null),
        private val gate: CompletableDeferred<Unit>? = null,
    ) : AiChatClient {
        val calls = mutableListOf<Call>()

        override suspend fun ask(
            question: String,
            transcript: String,
            history: List<AiChatMessage>,
        ): AiChatResult {
            calls += Call(question = question, transcript = transcript, history = history)
            gate?.await()
            return result
        }

        data class Call(
            val question: String,
            val transcript: String,
            val history: List<AiChatMessage>,
        )
    }

    private companion object {
        const val EPISODE = "episode-uuid"
        const val OTHER_EPISODE = "other-episode-uuid"
        const val PODCAST = "podcast-uuid"
    }
}
