package au.com.shiftyjelly.pocketcasts.models.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import au.com.shiftyjelly.pocketcasts.models.to.AdDetectionQueueItem
import kotlinx.coroutines.flow.Flow

/**
 * The durable ad-detection queue. Only [AdDetectionWorker] drains it, so transitions, failures and
 * backoffs are serialised while the network may come and go between passes.
 */
@Dao
abstract class AdDetectionQueueDao {
    @Query(
        "INSERT OR IGNORE INTO ad_detection_queue " +
            "(episode_uuid, enqueued_at, analyzed_until_ms, is_complete, attempts, next_attempt_at, last_error) " +
            "VALUES (:episodeUuid, :now, 0, 0, 0, 0, NULL)",
    )
    abstract suspend fun enqueue(episodeUuid: String, now: Long)

    @Query("SELECT * FROM ad_detection_queue WHERE episode_uuid IS :episodeUuid")
    abstract suspend fun find(episodeUuid: String): AdDetectionQueueItem?

    @Query(
        "SELECT * FROM ad_detection_queue WHERE is_complete IS 0 AND next_attempt_at <= :now " +
            "ORDER BY enqueued_at ASC LIMIT 1",
    )
    abstract suspend fun findNextDue(now: Long): AdDetectionQueueItem?

    @Query("SELECT COUNT(*) FROM ad_detection_queue WHERE is_complete IS 0 AND next_attempt_at <= :now")
    abstract suspend fun countDue(now: Long): Int

    @Query("SELECT COUNT(*) FROM ad_detection_queue WHERE is_complete IS 0")
    abstract suspend fun countPending(): Int

    /**
     * Wakes a finished or waiting episode for another pass. A failure's backoff is only shortened,
     * never removed, so a broken classifier is not hammered every time the recognizer writes a cue.
     */
    @Query(
        "UPDATE ad_detection_queue SET next_attempt_at = MIN(next_attempt_at, :capAt) " +
            "WHERE episode_uuid IS :episodeUuid AND is_complete IS 0",
    )
    abstract suspend fun requestRun(episodeUuid: String, capAt: Long)

    @Query(
        "UPDATE ad_detection_queue SET analyzed_until_ms = :analyzedUntilMs, is_complete = :isComplete, " +
            "next_attempt_at = 0, last_error = NULL WHERE episode_uuid IS :episodeUuid",
    )
    abstract suspend fun updateProgress(episodeUuid: String, analyzedUntilMs: Long, isComplete: Boolean)

    @Query(
        "UPDATE ad_detection_queue SET next_attempt_at = :nextAttemptAt " +
            "WHERE episode_uuid IS :episodeUuid AND is_complete IS 0",
    )
    abstract suspend fun defer(episodeUuid: String, nextAttemptAt: Long)

    @Query(
        "UPDATE ad_detection_queue SET attempts = attempts + 1, " +
            "next_attempt_at = :nextAttemptAt, last_error = :error WHERE episode_uuid IS :episodeUuid",
    )
    abstract suspend fun markFailure(episodeUuid: String, nextAttemptAt: Long, error: String?)

    @Query(
        "UPDATE ad_detection_queue SET analyzed_until_ms = 0, is_complete = 0, attempts = 0, " +
            "next_attempt_at = 0, last_error = NULL WHERE episode_uuid IS :episodeUuid",
    )
    abstract suspend fun reset(episodeUuid: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(item: AdDetectionQueueItem)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM ad_detection_queue WHERE episode_uuid IS :episodeUuid AND is_complete IS 0)",
    )
    abstract fun observeIsPending(episodeUuid: String): Flow<Boolean>

    @Query("DELETE FROM ad_detection_queue WHERE episode_uuid IN (:episodeUuids)")
    protected abstract suspend fun deleteForEpisodesUnsafe(episodeUuids: List<String>)

    suspend fun deleteForEpisodes(episodeUuids: Collection<String>) {
        episodeUuids.chunked(au.com.shiftyjelly.pocketcasts.models.db.AppDatabase.SQLITE_BIND_ARG_LIMIT)
            .forEach { deleteForEpisodesUnsafe(it) }
    }

    @Query("DELETE FROM ad_detection_queue")
    abstract suspend fun deleteAll()
}
