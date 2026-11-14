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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import au.com.shiftyjelly.pocketcasts.compose.AppTheme
import au.com.shiftyjelly.pocketcasts.compose.extensions.contentWithoutConsumedInsets
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.models.to.AdSegment
import au.com.shiftyjelly.pocketcasts.models.to.Transcript
import au.com.shiftyjelly.pocketcasts.models.to.TranscriptEntry
import au.com.shiftyjelly.pocketcasts.player.viewmodel.ShelfSharedViewModel
import au.com.shiftyjelly.pocketcasts.repositories.ads.AdSegmentManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.transcript.TranscriptManager
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/**
 * Episode transcript tab: timestamped cues that seek playback when tapped. Reads whatever the
 * transcript stack has for the current episode, including an on-device transcript produced by
 * the ad analysis pass. Sentences inside a detected ad segment are shown in italics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class TranscriptTabFragment : Fragment() {
    @Inject
    lateinit var transcriptManager: TranscriptManager

    @Inject
    lateinit var playbackManager: PlaybackManager

    @Inject
    lateinit var adSegmentManager: AdSegmentManager

    @Inject
    lateinit var theme: Theme

    private val shelfSharedViewModel: ShelfSharedViewModel by activityViewModels()
    private var state by mutableStateOf(TranscriptTabState())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return contentWithoutConsumedInsets {
            AppTheme(theme.activeTheme) {
                TranscriptTabPage(
                    entries = state.entries,
                    adSegments = state.adSegments,
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
                .flatMapLatest { episodeUuid -> observeTranscript(episodeUuid) }
                .collect { state = it }
        }
    }

    /**
     * Combines the transcript with the ad segments of the same episode, so a sentence restyles when
     * segments arrive after the transcript is shown and when the listener deselects one of them.
     */
    private fun observeTranscript(episodeUuid: String): Flow<TranscriptTabState> {
        return transcriptManager.observeIsTranscriptAvailable(episodeUuid)
            .flatMapLatest { isAvailable ->
                val entries = flow<List<TranscriptEntry.Text>> {
                    // Clearing first keeps the previous episode's transcript from showing meanwhile.
                    emit(emptyList())
                    if (isAvailable) {
                        emit(loadEntries(episodeUuid))
                    }
                }
                combine(entries, adSegmentManager.observeSegments(episodeUuid)) { transcriptEntries, adSegments ->
                    TranscriptTabState(entries = transcriptEntries, adSegments = adSegments)
                }
            }
    }

    private suspend fun loadEntries(episodeUuid: String): List<TranscriptEntry.Text> {
        val transcript = transcriptManager.loadTranscript(episodeUuid)
        return (transcript as? Transcript.Text)?.entries
            ?.filterIsInstance<TranscriptEntry.Text>()
            ?.filter { it.startTimeMs >= 0 }
            .orEmpty()
    }
}

private data class TranscriptTabState(
    val entries: List<TranscriptEntry.Text> = emptyList(),
    val adSegments: List<AdSegment> = emptyList(),
)

@Composable
private fun TranscriptTabPage(
    entries: List<TranscriptEntry.Text>,
    adSegments: List<AdSegment>,
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
    // Ad segment times are in the timeline of the played audio, the same timeline as the entries,
    // so they are compared as they are. Only italics mark an ad sentence, nothing else changes.
    val adSentenceIndexes = remember(entries, adSegments) {
        entries.indices.filter { isAdSentence(entries[it], adSegments) }.toSet()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(entries) { index, entry ->
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
                    fontStyle = if (index in adSentenceIndexes) FontStyle.Italic else null,
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
