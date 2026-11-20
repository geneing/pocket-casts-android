package au.com.shiftyjelly.pocketcasts.models.to

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * The classifier's verdict for one transcript cue: [probability] is the probability that the cue
 * belongs to an ad break, which already counts a transition verdict, and [contentProbability] is
 * the probability that it is editorial.
 *
 * Scores are stored for every cue, not only the ones inside a detected ad, so the transcript can
 * show how the model read each sentence and not just where it decided an ad was. Keeping content
 * with it lets a pod's edges be widened from the stored scores, without asking the classifier
 * again.
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
    @ColumnInfo(name = "content_probability") val contentProbability: Float? = null,
)
