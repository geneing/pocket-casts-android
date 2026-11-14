package au.com.shiftyjelly.pocketcasts.models.to

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * A promotional, advertising, or sponsor segment inside an episode. Timestamps come from the
 * on-device analysis pass and are already in the timeline of the audio being played, so they can be
 * used for playback directly.
 */
@Entity(
    tableName = "episode_ad_segments",
    primaryKeys = ["episode_uuid", "segment_index"],
    indices = [
        Index(name = "ad_segment_episode_uuid_index", value = ["episode_uuid"]),
    ],
)
data class DbAdSegment(
    @ColumnInfo(name = "segment_index") val index: Int,
    @ColumnInfo(name = "episode_uuid") val episodeUuid: String,
    @ColumnInfo(name = "start_time") val startTimeMs: Long,
    @ColumnInfo(name = "end_time") val endTimeMs: Long,
    @ColumnInfo(name = "kind") val kind: AdSegmentKind = AdSegmentKind.Unknown,
    @ColumnInfo(name = "origin") val origin: AdSegmentOrigin = AdSegmentOrigin.Unknown,
    @ColumnInfo(name = "confidence") val confidence: Float = 0f,
    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "deselected", defaultValue = "0") val deselected: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
)
