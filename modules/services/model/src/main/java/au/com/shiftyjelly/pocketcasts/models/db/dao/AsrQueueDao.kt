package au.com.shiftyjelly.pocketcasts.models.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import au.com.shiftyjelly.pocketcasts.models.to.AsrQueueItem
import kotlinx.coroutines.flow.Flow

/**
 * The durable on-device recognition queue. Only [AsrQueueWorker] drains it, one row at a time, so
 * the table is also the record of how far each transcript was recognised.
 */
@Dao
abstract class AsrQueueDao {
    /** Adds the episode unless it is already queued or already recognised. */
    @Query(
        "INSERT OR IGNORE INTO episode_asr_queue " +
            "(episode_uuid, enqueued_at, processed_until_ms, is_complete, attempts, next_attempt_at, last_error) " +
            "VALUES (:episodeUuid, :now, 0, 0, 0, 0, NULL)",
    )
    abstract suspend fun enqueue(episodeUuid: String, now: Long)

    /**
     * Registers a transcript written before the queue existed as already recognised, so an upgrade
     * does not run the recognizer over every cached transcript again.
     */
    @Query(
        "INSERT OR IGNORE INTO episode_asr_queue " +
            "(episode_uuid, enqueued_at, processed_until_ms, is_complete, attempts, next_attempt_at, last_error) " +
            "VALUES (:episodeUuid, :now, :processedUntilMs, 1, 0, 0, NULL)",
    )
    abstract suspend fun enqueueCompleted(episodeUuid: String, processedUntilMs: Long, now: Long)

    @Query("SELECT * FROM episode_asr_queue WHERE episode_uuid IS :episodeUuid")
    abstract suspend fun find(episodeUuid: String): AsrQueueItem?

    @Query(
        "SELECT * FROM episode_asr_queue WHERE is_complete IS 0 AND next_attempt_at <= :now " +
            "ORDER BY enqueued_at ASC LIMIT 1",
    )
    abstract suspend fun findNextDue(now: Long): AsrQueueItem?

    @Query("SELECT COUNT(*) FROM episode_asr_queue WHERE is_complete IS 0 AND next_attempt_at <= :now")
    abstract suspend fun countDue(now: Long): Int

    @Query("SELECT COUNT(*) FROM episode_asr_queue WHERE is_complete IS 0")
    abstract suspend fun countPending(): Int

    @Query("UPDATE episode_asr_queue SET processed_until_ms = :processedUntilMs, last_error = NULL WHERE episode_uuid IS :episodeUuid")
    abstract suspend fun updateProgress(episodeUuid: String, processedUntilMs: Long)

    @Query(
        "UPDATE episode_asr_queue SET is_complete = 1, processed_until_ms = :processedUntilMs, " +
            "next_attempt_at = 0, last_error = NULL WHERE episode_uuid IS :episodeUuid",
    )
    abstract suspend fun markComplete(episodeUuid: String, processedUntilMs: Long)

    @Query(
        "UPDATE episode_asr_queue SET attempts = attempts + 1, " +
            "next_attempt_at = :nextAttemptAt, last_error = :error WHERE episode_uuid IS :episodeUuid",
    )
    abstract suspend fun markFailure(episodeUuid: String, nextAttemptAt: Long, error: String?)

    /**
     * Makes a row that is parked waiting for its audio due now, and only such a row.
     *
     * The park's own retry time is only a safety net for a download that never finishes; the
     * download path calls this when it writes the file, so recognition starts on that audio at once
     * instead of at the end of a timeout the download may outlive.
     */
    @Query(
        "UPDATE episode_asr_queue SET next_attempt_at = :now, last_error = NULL " +
            "WHERE episode_uuid IS :episodeUuid AND is_complete IS 0 AND last_error IS :reason",
    )
    abstract suspend fun wakeWaitingForAudio(episodeUuid: String, now: Long, reason: String)

    /** Restarts recognition from the beginning, used when the audio or the engine changed. */
    @Query(
        "UPDATE episode_asr_queue SET processed_until_ms = 0, is_complete = 0, attempts = 0, " +
            "next_attempt_at = 0, last_error = NULL WHERE episode_uuid IS :episodeUuid",
    )
    abstract suspend fun reset(episodeUuid: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(item: AsrQueueItem)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM episode_asr_queue WHERE episode_uuid IS :episodeUuid AND is_complete IS 0)",
    )
    abstract fun observeIsPending(episodeUuid: String): Flow<Boolean>

    @Query("DELETE FROM episode_asr_queue WHERE episode_uuid IN (:episodeUuids)")
    protected abstract suspend fun deleteForEpisodesUnsafe(episodeUuids: List<String>)

    suspend fun deleteForEpisodes(episodeUuids: Collection<String>) {
        episodeUuids.chunked(au.com.shiftyjelly.pocketcasts.models.db.AppDatabase.SQLITE_BIND_ARG_LIMIT)
            .forEach { deleteForEpisodesUnsafe(it) }
    }

    @Query("DELETE FROM episode_asr_queue")
    abstract suspend fun deleteAll()
}
