package au.com.shiftyjelly.pocketcasts.repositories.ads

import au.com.shiftyjelly.pocketcasts.models.to.AdCueScore
import au.com.shiftyjelly.pocketcasts.models.to.AdSegment
import kotlinx.coroutines.flow.Flow

interface AdSegmentManager {
    /** Segments for an episode, in the timeline of the audio being played. */
    fun observeSegments(episodeUuid: String): Flow<List<AdSegment>>

    /** The classifier's advertisement probability for every transcript cue of the episode. */
    fun observeCueScores(episodeUuid: String): Flow<List<AdCueScore>>

    /** Starts (or resumes) the on-device analysis for an episode in the background. */
    fun analyze(episodeUuid: String)

    suspend fun hasSegments(episodeUuid: String): Boolean

    suspend fun setDeselected(episodeUuid: String, segmentIndex: Int, deselected: Boolean)

    suspend fun clear(episodeUuid: String)
}
