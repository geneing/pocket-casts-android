package au.com.shiftyjelly.pocketcasts.models.to

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * The classifier's advertisement probability for one transcript cue.
 *
 * Scores are stored for every cue, not only the ones inside a detected ad, so the transcript can
 * show how the model read each sentence and not just where it decided an ad was.
 */
@Entity(
    tableName = "episode_ad_cue_scores",
    primaryKeys = ["episode_uuid", "cue_index"],
    indices = [
        Index(name = "ad_cue_score_episode_uuid_index", value = ["episode_uuid"]),
    ],
)
data class DbAdCueScore(
    @ColumnInfo(name = "episode_uuid") val episodeUuid: String,
    @ColumnInfo(name = "cue_index") val cueIndex: Int,
    @ColumnInfo(name = "start_time") val startTimeMs: Long,
    @ColumnInfo(name = "end_time") val endTimeMs: Long,
    @ColumnInfo(name = "probability") val probability: Float,
)
