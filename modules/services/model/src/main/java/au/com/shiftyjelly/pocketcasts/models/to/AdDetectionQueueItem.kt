package au.com.shiftyjelly.pocketcasts.models.to

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One episode waiting for the on-device transcript to be scored for ads.
 *
 * [analyzedUntilMs] is the end of the last cue that was sent to the classifier, so a failed or
 * interrupted request resumes from that cue instead of paying for the whole transcript again. The
 * queue is drained by a single worker: detection needs the network, and several playlist episodes
 * must not race for the same API budget.
 */
@Entity(tableName = "ad_detection_queue")
data class AdDetectionQueueItem(
    @PrimaryKey @ColumnInfo(name = "episode_uuid") val episodeUuid: String,
    @ColumnInfo(name = "enqueued_at") val enqueuedAt: Long,
    @ColumnInfo(name = "analyzed_until_ms", defaultValue = "0") val analyzedUntilMs: Long = 0L,
    @ColumnInfo(name = "is_complete", defaultValue = "0") val isComplete: Boolean = false,
    @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_at", defaultValue = "0") val nextAttemptAt: Long = 0L,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)
