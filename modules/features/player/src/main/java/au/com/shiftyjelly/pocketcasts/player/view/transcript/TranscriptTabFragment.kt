package au.com.shiftyjelly.pocketcasts.player.view.transcript

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/**
 * Episode transcript tab: timestamped cues that seek playback when tapped. Reads whatever the
 * transcript stack has for the episode, including an on-device transcript produced by the ad
 * analysis pass.
 *
 * With [forEpisode] the tab shows a fixed episode and reads like any other tab of an episode's
 * details screen: no playback is running yet, so a cue does not seek and the page uses the app
 * colors instead of the player's dark background. Without it the tab follows the player's episode.
 * Sentences inside a detected ad segment are shown in italics, with the segment's advertisement
 * probability next to their timestamp.
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
        val isPlayerTab = fixedEpisodeUuid == null
        val onSeek: ((Int) -> Unit)? = if (isPlayerTab) {
            { positionMs -> playbackManager.seekToTimeMs(positionMs) }
        } else {
            null
        }
        return contentWithoutConsumedInsets {
            AppTheme(theme.activeTheme) {
                TranscriptTabPage(
                    entries = state.entries,
                    adSegments = state.adSegments,
                    onSeek = onSeek,
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
            episodeFlow
                .flatMapLatest { episodeUuid -> observeTranscript(episodeUuid) }
                .collect { state = it }
        }
    }

    private val fixedEpisodeUuid: String?
        get() = arguments?.getString(ARG_EPISODE_UUID)

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

    companion object {
        private const val ARG_EPISODE_UUID = "episode_uuid"

        fun forEpisode(episodeUuid: String) = TranscriptTabFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_EPISODE_UUID, episodeUuid)
            }
        }
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
    onSeek: ((Int) -> Unit)?,
    usePlayerColors: Boolean,
) {
    // The player tab sits on a background that is dark in every theme, so it uses the player
    // contrast colors rather than the app theme's text color which would be unreadable there.
    val colors = MaterialTheme.theme.colors
    val textColor = if (usePlayerColors) colors.playerContrast01 else colors.primaryText01
    val timestampColor = if (usePlayerColors) colors.playerContrast02 else colors.primaryText02
    if (entries.isEmpty()) {
        Text(
            text = stringResource(LR.string.transcript_not_available),
            style = MaterialTheme.typography.body2,
            color = timestampColor,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    // Ad segment times are in the timeline of the played audio, the same timeline as the entries,
    // so they are compared as they are. Italics mark an ad sentence, and the segment's probability
    // is shown beside its timestamp so the detection can be followed without opening anything else.
    val adSegmentBySentence = remember(entries, adSegments) {
        entries.indices.associateWith { index -> adSegmentFor(entries[index], adSegments) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(entries) { index, entry ->
            val adSegment = adSegmentBySentence[index]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onSeek != null) {
                            Modifier.clickable { onSeek(entry.startTimeMs.toInt()) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = entry.value,
                    style = MaterialTheme.typography.body2,
                    color = textColor,
                    fontStyle = if (adSegment != null) FontStyle.Italic else null,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTimestamp(entry.startTimeMs),
                        style = MaterialTheme.typography.caption,
                        color = timestampColor,
                    )
                    if (adSegment != null) {
                        AdProbabilityLabel(confidence = adSegment.confidence)
                    }
                }
            }
        }
    }
}

/** The model's advertisement probability for a sentence's segment, as a compact percentage pill. */
@Composable
private fun AdProbabilityLabel(confidence: Float) {
    val colors = MaterialTheme.theme.colors
    val percent = (confidence * 100).roundToInt().coerceIn(0, 100)
    Text(
        text = stringResource(LR.string.player_ad_probability, percent),
        style = MaterialTheme.typography.caption,
        color = colors.primaryInteractive01,
        modifier = Modifier
            .padding(start = 8.dp)
            .background(colors.primaryInteractive01.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun formatTimestamp(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
