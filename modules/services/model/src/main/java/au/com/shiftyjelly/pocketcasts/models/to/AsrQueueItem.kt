package au.com.shiftyjelly.pocketcasts.models.to

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One episode waiting for the on-device recognizer.
 *
 * The table is the durable ASR queue: a single worker drains it one episode at a time, so adding
 * several episodes to the playlist can never start several recognizer passes at once. Progress is
 * the end of the last cue already written, which is where a later pass resumes after a kill, a
 * failure or a network drop. Rows are kept after completion so a finished transcript is not
 * recognised again on the next launch.
 */
@Entity(tableName = "episode_asr_queue")
data class AsrQueueItem(
    @PrimaryKey @ColumnInfo(name = "episode_uuid") val episodeUuid: String,
    @ColumnInfo(name = "enqueued_at") val enqueuedAt: Long,
    @ColumnInfo(name = "processed_until_ms", defaultValue = "0") val processedUntilMs: Long = 0L,
    @ColumnInfo(name = "is_complete", defaultValue = "0") val isComplete: Boolean = false,
    @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_at", defaultValue = "0") val nextAttemptAt: Long = 0L,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)
