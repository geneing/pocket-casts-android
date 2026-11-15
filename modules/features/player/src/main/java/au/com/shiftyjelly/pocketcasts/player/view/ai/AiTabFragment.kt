package au.com.shiftyjelly.pocketcasts.player.view.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import au.com.shiftyjelly.pocketcasts.compose.AppTheme
import au.com.shiftyjelly.pocketcasts.compose.extensions.contentWithoutConsumedInsets
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.player.viewmodel.ShelfSharedViewModel
import au.com.shiftyjelly.pocketcasts.repositories.ai.AiChatMessage
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/**
 * Episode AI tab: a chat over an episode's transcript, shown next to the transcript tab and only
 * when there is a transcript to chat over.
 *
 * With [forEpisode] the tab chats about a fixed episode and uses the app colors, so it also works
 * on an episode details screen where nothing is playing. Without it the tab follows the player's
 * episode and uses the player's dark-background colors.
 */
@AndroidEntryPoint
class AiTabFragment : Fragment() {
    @Inject
    lateinit var theme: Theme

    private val shelfSharedViewModel: ShelfSharedViewModel by activityViewModels()
    private val viewModel: AiTabViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val isPlayerTab = fixedEpisodeUuid == null
        return contentWithoutConsumedInsets {
            AppTheme(theme.activeTheme) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                AiTabPage(
                    state = state,
                    onInputChange = viewModel::onInputChange,
                    onSend = viewModel::send,
                    usePlayerColors = isPlayerTab,
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val episodeFlow = fixedEpisodeUuid?.let(::flowOf)
            ?: shelfSharedViewModel.uiState
                .mapNotNull { it.episode?.uuid }
                .distinctUntilChanged()
        viewLifecycleOwner.lifecycleScope.launch {
            episodeFlow.collect { episodeUuid -> viewModel.onEpisodeChanged(episodeUuid) }
        }
    }

    private val fixedEpisodeUuid: String?
        get() = arguments?.getString(ARG_EPISODE_UUID)

    companion object {
        private const val ARG_EPISODE_UUID = "episode_uuid"

        fun forEpisode(episodeUuid: String) = AiTabFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_EPISODE_UUID, episodeUuid)
            }
        }
    }
}

@Composable
private fun AiTabPage(
    state: AiTabState,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    usePlayerColors: Boolean,
) {
    val colors = aiTabColors(usePlayerColors)
    Column(modifier = Modifier.fillMaxSize()) {
        when (state.transcript) {
            TranscriptAvailability.Loading -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    CircularProgressIndicator(
                        color = colors.muted,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            TranscriptAvailability.Missing -> {
                Text(
                    text = stringResource(LR.string.player_ai_transcript_missing),
                    style = MaterialTheme.typography.body2,
                    color = colors.muted,
                    modifier = Modifier.padding(24.dp),
                )
            }

            TranscriptAvailability.Available -> {
                if (state.isConversationEmpty) {
                    EmptyConversation(
                        onSend = onSend,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    MessageList(
                        state = state,
                        colors = colors,
                        modifier = Modifier.weight(1f),
                    )
                }
                state.error?.let { error ->
                    ErrorLine(error = error)
                }
                val input = state.input ?: stringResource(LR.string.player_ai_default_question)
                InputBar(
                    input = input,
                    canSend = state.canSend && input.isNotBlank(),
                    onInputChange = onInputChange,
                    onSend = onSend,
                    colors = colors,
                )
            }
        }
    }
}

@Composable
private fun EmptyConversation(
    onSend: (String) -> Unit,
    colors: AiTabColors,
    modifier: Modifier = Modifier,
) {
    val suggestion = stringResource(LR.string.player_ai_default_question)
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(LR.string.player_ai_empty_hint),
            style = MaterialTheme.typography.body2,
            color = colors.muted,
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.assistantBubble,
            modifier = Modifier.clickable { onSend(suggestion) },
        ) {
            Text(
                text = suggestion,
                style = MaterialTheme.typography.body2,
                color = colors.text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MessageList(
    state: AiTabState,
    colors: AiTabColors,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Stick to the newest message, and to the typing indicator while an answer is on its way.
    LaunchedEffect(state.messages.size, state.isLoading) {
        val lastIndex = state.messages.lastIndex + if (state.isLoading) 1 else 0
        if (lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(state.messages) { message ->
            MessageBubble(message = message, colors = colors)
        }
        if (state.isLoading) {
            item { ThinkingRow(colors = colors) }
        }
    }
}

@Composable
private fun MessageBubble(
    message: AiChatMessage,
    colors: AiTabColors,
) {
    val isUser = message.role == AiChatMessage.Role.User
    Row(
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isUser) colors.userBubble else colors.assistantBubble,
            modifier = Modifier.fillMaxWidth(BUBBLE_WIDTH_FRACTION),
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.body2,
                color = colors.text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ThinkingRow(colors: AiTabColors) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        CircularProgressIndicator(
            color = colors.muted,
            strokeWidth = 2.dp,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(LR.string.player_ai_thinking),
            style = MaterialTheme.typography.caption,
            color = colors.muted,
        )
    }
}

@Composable
private fun ErrorLine(error: AiTabError) {
    val message = when (error) {
        AiTabError.Unavailable -> stringResource(LR.string.player_ai_unavailable)
        AiTabError.RequestFailed -> stringResource(LR.string.player_ai_error)
    }
    Text(
        text = message,
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun InputBar(
    input: String,
    canSend: Boolean,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    colors: AiTabColors,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val send: () -> Unit = {
        onSend(input)
        keyboardController?.hide()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            placeholder = {
                Text(
                    text = stringResource(LR.string.player_ai_input_hint),
                    style = MaterialTheme.typography.body2,
                    color = colors.hint,
                )
            },
            textStyle = MaterialTheme.typography.body2,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) send() }),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = colors.text,
                cursorColor = colors.text,
                focusedBorderColor = colors.muted,
                unfocusedBorderColor = colors.border,
            ),
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { if (canSend) send() },
            enabled = canSend,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(44.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(LR.string.player_ai_send),
                tint = if (canSend) colors.text else colors.hint,
            )
        }
    }
}

private data class AiTabColors(
    val text: Color,
    val muted: Color,
    val hint: Color,
    val border: Color,
    val userBubble: Color,
    val assistantBubble: Color,
)

/**
 * The player tab sits on a background that is dark in every theme, so it uses the player contrast
 * colors. An episode details tab follows the app theme instead.
 */
@Composable
private fun aiTabColors(usePlayerColors: Boolean): AiTabColors {
    val colors = MaterialTheme.theme.colors
    return if (usePlayerColors) {
        AiTabColors(
            text = colors.playerContrast01,
            muted = colors.playerContrast02,
            hint = colors.playerContrast03,
            border = colors.playerContrast04,
            userBubble = colors.playerContrast05,
            assistantBubble = colors.playerContrast06,
        )
    } else {
        AiTabColors(
            text = colors.primaryText01,
            muted = colors.primaryText02,
            hint = colors.primaryIcon03,
            border = colors.primaryUi05,
            userBubble = colors.primaryUi05,
            assistantBubble = colors.primaryUi04,
        )
    }
}

private const val BUBBLE_WIDTH_FRACTION = 0.88f
