package au.com.shiftyjelly.pocketcasts.player.view.transcript

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import au.com.shiftyjelly.pocketcasts.compose.AppTheme
import au.com.shiftyjelly.pocketcasts.compose.extensions.contentWithoutConsumedInsets
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.models.to.Transcript
import au.com.shiftyjelly.pocketcasts.models.to.TranscriptEntry
import au.com.shiftyjelly.pocketcasts.player.viewmodel.ShelfSharedViewModel
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.transcript.TranscriptManager
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/**
 * Episode transcript tab: timestamped cues that seek playback when tapped. Reads whatever the
 * transcript stack has for the current episode, including an on-device transcript produced by
 * the ad analysis pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class TranscriptTabFragment : Fragment() {
    @Inject
    lateinit var transcriptManager: TranscriptManager

    @Inject
    lateinit var playbackManager: PlaybackManager

    @Inject
    lateinit var theme: Theme

    private val shelfSharedViewModel: ShelfSharedViewModel by activityViewModels()
    private var entries by mutableStateOf<List<TranscriptEntry.Text>>(emptyList())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return contentWithoutConsumedInsets {
            AppTheme(theme.activeTheme) {
                TranscriptTabPage(
                    entries = entries,
                    onSeek = { positionMs -> playbackManager.seekToTimeMs(positionMs) },
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            shelfSharedViewModel.uiState
                .mapNotNull { it.episode?.uuid }
                .distinctUntilChanged()
                .flatMapLatest { episodeUuid ->
                    transcriptManager.observeIsTranscriptAvailable(episodeUuid).map { episodeUuid }
                }
                .collect { episodeUuid ->
                    entries = emptyList()
                    val transcript = transcriptManager.loadTranscript(episodeUuid)
                    entries = (transcript as? Transcript.Text)?.entries
                        ?.filterIsInstance<TranscriptEntry.Text>()
                        ?.filter { it.startTimeMs >= 0 }
                        .orEmpty()
                }
        }
    }
}

@Composable
private fun TranscriptTabPage(
    entries: List<TranscriptEntry.Text>,
    onSeek: (Int) -> Unit,
) {
    // The tab sits on the player background, which is dark in every theme, so it uses the player
    // contrast colors rather than the app theme's text color which would be unreadable here.
    val colors = MaterialTheme.theme.colors
    if (entries.isEmpty()) {
        Text(
            text = stringResource(LR.string.transcript_not_available),
            style = MaterialTheme.typography.body2,
            color = colors.playerContrast02,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries) { entry ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeek(entry.startTimeMs.toInt()) }
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = entry.value,
                    style = MaterialTheme.typography.body2,
                    color = colors.playerContrast01,
                )
                Text(
                    text = formatTimestamp(entry.startTimeMs),
                    style = MaterialTheme.typography.caption,
                    color = colors.playerContrast02,
                )
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
