package au.com.shiftyjelly.pocketcasts.player.view.transcript

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import au.com.shiftyjelly.pocketcasts.compose.AppTheme
import au.com.shiftyjelly.pocketcasts.compose.extensions.contentWithoutConsumedInsets
import au.com.shiftyjelly.pocketcasts.compose.extensions.verticalScrollBar
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.models.to.AdCueScore
import au.com.shiftyjelly.pocketcasts.models.to.AdSegment
import au.com.shiftyjelly.pocketcasts.models.to.Transcript
import au.com.shiftyjelly.pocketcasts.models.to.TranscriptEntry
import au.com.shiftyjelly.pocketcasts.player.viewmodel.ShelfSharedViewModel
import au.com.shiftyjelly.pocketcasts.repositories.ads.AdSegmentManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.transcript.TranscriptManager
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
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
import kotlinx.coroutines.flow.map
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

    /** Where playback is, so the page can keep the spoken sentence in view. */
    private var playbackPositionMs by mutableIntStateOf(0)

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
                    cueScores = state.cueScores,
                    onSeek = onSeek,
                    usePlayerColors = isPlayerTab,
                    playbackPositionMs = if (isPlayerTab) playbackPositionMs else null,
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
        if (fixedEpisodeUuid == null) {
            // The player tab follows playback, so it needs the position even while the user is
            // reading further down; see the page for how that is allowed to pause.
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    playbackManager.playbackStateFlow
                        .map { it.positionMs }
                        .distinctUntilChanged()
                        .collect { playbackPositionMs = it }
                }
            }
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
                        // The tab is often the first place an episode is read, before it is played.
                        // Starting the analysis here fills in the probabilities without waiting.
                        if (FeatureFlag.isEnabled(Feature.AD_SKIPPING)) {
                            adSegmentManager.analyze(episodeUuid)
                        }
                        emit(loadEntries(episodeUuid))
                    }
                }
                combine(
                    entries,
                    adSegmentManager.observeSegments(episodeUuid),
                    adSegmentManager.observeCueScores(episodeUuid),
                ) { transcriptEntries, adSegments, cueScores ->
                    TranscriptTabState(entries = transcriptEntries, adSegments = adSegments, cueScores = cueScores)
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
    val cueScores: List<AdCueScore> = emptyList(),
)

@Composable
private fun TranscriptTabPage(
    entries: List<TranscriptEntry.Text>,
    adSegments: List<AdSegment>,
    cueScores: List<AdCueScore>,
    onSeek: ((Int) -> Unit)?,
    usePlayerColors: Boolean,
    playbackPositionMs: Int?,
) {
    // The player tab sits on a background that is dark in every theme, so it uses the player
    // contrast colors rather than the app theme's text color which would be unreadable there.
    val colors = MaterialTheme.theme.colors
    val textColor = if (usePlayerColors) colors.playerContrast01 else colors.primaryText01
    val timestampColor = if (usePlayerColors) colors.playerContrast02 else colors.primaryText02
    val highlightColor = if (usePlayerColors) colors.playerContrast06 else colors.primaryUi05
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
    // so they are compared as they are. Italics mark an ad sentence, and every scored sentence
    // shows the classifier's probability beside its timestamp so the read of the whole transcript
    // can be followed, not just the pods it decided were ads.
    val adSegmentBySentence = remember(entries, adSegments) {
        entries.indices.associateWith { index -> adSegmentFor(entries[index], adSegments) }
    }
    val probabilityBySentence = remember(entries, cueScores) {
        entries.indices.associateWith { index -> cueProbabilityFor(entries[index], cueScores) }
    }
    val listState = rememberLazyListState()
    val contentPadding = PaddingValues(16.dp)
    // The sentence being spoken right now: the last one that has started. Reading follows it until
    // the reader scrolls away, which is what the button below the list is for.
    val activeIndex = remember(entries, playbackPositionMs) {
        playbackPositionMs?.let { positionMs -> entries.indexOfLast { it.startTimeMs <= positionMs } } ?: -1
    }
    var followPlayback by remember(entries) { mutableStateOf(true) }
    LaunchedEffect(followPlayback, activeIndex) {
        if (followPlayback && activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex)
        }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            // Only a finger pulling the list counts; the scroll this page does itself also reports
            // progress, and treating that as the reader leaving would stop the follow immediately.
            if (interaction is DragInteraction.Start) followPlayback = false
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .consumeUpwardDrags()
                .verticalScrollBar(
                    scrollState = listState,
                    thumbColor = timestampColor,
                    contentPadding = contentPadding,
                ),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(entries) { index, entry ->
                val adSegment = adSegmentBySentence[index]
                val probability = probabilityBySentence[index]
                val isPlaying = index == activeIndex
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (isPlaying) highlightColor else Color.Transparent,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .then(
                            if (onSeek != null) {
                                Modifier.clickable { onSeek(entry.startTimeMs.toInt()) }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
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
                        if (probability != null) {
                            AdProbabilityLabel(
                                probability = probability,
                                isAd = adSegment != null,
                                usePlayerColors = usePlayerColors,
                            )
                        }
                    }
                }
            }
        }
        if (followPlayback.not() && activeIndex >= 0) {
            Button(
                onClick = { followPlayback = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Text(stringResource(LR.string.transcript_jump_to_current))
            }
        }
    }
}

/** A sentence's advertisement probability as a compact percentage pill. */
@Composable
private fun AdProbabilityLabel(
    probability: Float,
    isAd: Boolean,
    usePlayerColors: Boolean,
) {
    val colors = MaterialTheme.theme.colors
    val percent = (probability * 100).roundToInt().coerceIn(0, 100)
    val contentColor = when {
        isAd -> colors.primaryInteractive01
        usePlayerColors -> colors.playerContrast02
        else -> colors.primaryText02
    }
    val backgroundColor = when {
        isAd -> colors.primaryInteractive01.copy(alpha = 0.12f)
        usePlayerColors -> colors.playerContrast06
        else -> colors.primaryUi05
    }
    Text(
        text = stringResource(
            if (isAd) LR.string.player_ad_probability else LR.string.transcript_ad_score,
            percent,
        ),
        style = MaterialTheme.typography.caption,
        color = contentColor,
        modifier = Modifier
            .padding(start = 8.dp)
            .background(backgroundColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun formatTimestamp(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Keeps an upward drag inside this list instead of letting it drag the sheet behind it.
 *
 * The transcript tab is a page of a bottom sheet, and the sheet treats a drag the list did not use
 * as its own gesture: reading further down the text also pulls the screen up under the finger, which
 * feels like the transcript is changing screens instead of scrolling. Everything the list could not
 * use arrives at this connection before the sheet sees it, so consuming it there ends the gesture at
 * the text. Downward drags are left alone: the sheet still closes the way it always did.
 */
private fun Modifier.consumeUpwardDrags(): Modifier = nestedScroll(
    object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Negative y is the finger moving up, which is the direction that pulls a sheet open.
            return if (available.y < 0f) Offset(0f, available.y) else Offset.Zero
        }
    },
)
