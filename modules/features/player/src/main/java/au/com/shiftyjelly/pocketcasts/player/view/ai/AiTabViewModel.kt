package au.com.shiftyjelly.pocketcasts.player.view.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.models.to.TranscriptEntry
import au.com.shiftyjelly.pocketcasts.repositories.ai.AiChatClient
import au.com.shiftyjelly.pocketcasts.repositories.ai.AiChatMessage
import au.com.shiftyjelly.pocketcasts.repositories.ai.AiChatResult
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.shownotes.ShowNotesManager
import au.com.shiftyjelly.pocketcasts.repositories.transcript.TranscriptManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Chat over the current episode's transcript.
 *
 * The transcript is loaded once per episode and capped in the ViewModel, so a follow-up question
 * does not re-read it and cannot send an unbounded prompt. Switching episodes starts a fresh
 * conversation: the previous episode's transcript would answer the wrong questions.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class AiTabViewModel @Inject constructor(
    private val chatClient: AiChatClient,
    private val transcriptManager: TranscriptManager,
    private val episodeManager: EpisodeManager,
    private val showNotesManager: ShowNotesManager,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AiTabState())
    val state: StateFlow<AiTabState> = mutableState.asStateFlow()

    private var episodeUuid: String? = null
    private var transcriptContext: String? = null
    private var transcriptJob: Job? = null
    private var answerJob: Job? = null

    fun onEpisodeChanged(episodeUuid: String?) {
        if (episodeUuid == this.episodeUuid) return
        this.episodeUuid = episodeUuid
        transcriptContext = null
        transcriptJob?.cancel()
        answerJob?.cancel()
        mutableState.value = AiTabState()
        if (episodeUuid == null) {
            mutableState.update { it.copy(transcript = TranscriptAvailability.Missing) }
            return
        }
        viewModelScope.launch {
            try {
                episodeManager.findByUuid(episodeUuid)?.let { episode ->
                    showNotesManager.loadShowNotes(episode.podcastUuid, episodeUuid)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Recognition can still supply a transcript when show notes are unavailable.
            }
        }
        transcriptJob = viewModelScope.launch {
            merge(
                transcriptManager.observeIsTranscriptAvailable(episodeUuid).map { },
                transcriptManager.observeTranscriptUpdated(episodeUuid),
            ).onStart { emit(Unit) }.collect {
                if (transcriptContext == null) loadTranscript(episodeUuid)
            }
        }
    }

    /** Keeps the field's text in the state so it survives configuration changes and episode switches. */
    fun onInputChange(input: String) {
        mutableState.update { it.copy(input = input) }
    }

    /**
     * Sends [question]. Ignored while an answer is in flight, when the question is blank, and when
     * there is no transcript to answer from.
     */
    fun send(question: String) {
        val current = mutableState.value
        val context = transcriptContext
        val text = question.trim()
        if (text.isEmpty() || current.isLoading || context == null) return

        mutableState.value = current.copy(
            messages = current.messages + AiChatMessage(AiChatMessage.Role.User, text),
            input = "",
            isLoading = true,
            error = null,
        )
        answerJob?.cancel()
        answerJob = viewModelScope.launch {
            when (val result = chatClient.ask(question = text, transcript = context, history = current.messages)) {
                is AiChatResult.Answer -> mutableState.update { state ->
                    state.copy(
                        messages = state.messages + AiChatMessage(AiChatMessage.Role.Assistant, result.text),
                        isLoading = false,
                        error = null,
                    )
                }

                AiChatResult.NoApiKey -> mutableState.update { it.copy(isLoading = false, error = AiTabError.Unavailable) }

                is AiChatResult.Failed -> mutableState.update { it.copy(isLoading = false, error = AiTabError.RequestFailed) }
            }
        }
    }

    private suspend fun loadTranscript(episodeUuid: String) {
        val transcript = runCatching { transcriptManager.loadChatTranscript(episodeUuid) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                null
            }
        // The episode may have changed while the transcript was being read.
        if (episodeUuid != this.episodeUuid) return

        val text = transcript
            ?.entries
            ?.filterIsInstance<TranscriptEntry.Text>()
            ?.joinToString(separator = "\n") { entry -> entry.value.trim() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (text == null) {
            mutableState.update { it.copy(transcript = TranscriptAvailability.Missing) }
        } else {
            transcriptContext = capTranscript(text)
            mutableState.update { it.copy(transcript = TranscriptAvailability.Available) }
        }
    }
}

data class AiTabState(
    val messages: List<AiChatMessage> = emptyList(),
    /** The field's text; null means the tab still shows its suggested first question. */
    val input: String? = null,
    val isLoading: Boolean = false,
    val error: AiTabError? = null,
    val transcript: TranscriptAvailability = TranscriptAvailability.Loading,
) {
    val isConversationEmpty: Boolean get() = messages.isEmpty()

    val canSend: Boolean get() = transcript == TranscriptAvailability.Available && !isLoading
}

enum class TranscriptAvailability {
    Loading,
    Available,

    /** No transcript, or only a web/HTML one: there is nothing to chat over. */
    Missing,
}

enum class AiTabError {
    /** The build has no OpenRouter key, so the tab cannot ask anything. */
    Unavailable,
    RequestFailed,
}

/** The longest transcript sent to the model, in characters. */
internal const val MAX_TRANSCRIPT_CHARS = 800_000

internal const val TRANSCRIPT_TRUNCATION_MARKER = "\n\n[... transcript truncated ...]\n\n"

/**
 * Caps [text] to [maxChars] characters, keeping its head and its tail: an episode's opening and
 * its closing remarks are the parts a question is most likely about. The marker between them tells
 * the model that material was left out, so it does not claim the transcript is continuous.
 */
internal fun capTranscript(text: String, maxChars: Int = MAX_TRANSCRIPT_CHARS): String {
    if (text.length <= maxChars) return text
    val budget = (maxChars - TRANSCRIPT_TRUNCATION_MARKER.length).coerceAtLeast(2)
    val headLength = budget / 2
    return text.take(headLength) + TRANSCRIPT_TRUNCATION_MARKER + text.takeLast(budget - headLength)
}
