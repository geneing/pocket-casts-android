package au.com.shiftyjelly.pocketcasts.models.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import au.com.shiftyjelly.pocketcasts.models.db.AppDatabase
import au.com.shiftyjelly.pocketcasts.models.entity.Transcript
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TranscriptDao {
    @Query(
        "DELETE FROM episode_transcript WHERE episode_uuid IN (:episodeUuids) AND " +
            "url NOT LIKE 'local-generated://%'",
    )
    protected abstract suspend fun deleteRemoteForEpisodesUnsafe(episodeUuids: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertAll(transcripts: List<Transcript>)

    /**
     * Replaces the transcripts that come from the server, keeping the ones produced on the device.
     * A show notes refresh only knows about server transcripts, and it must not take away a
     * transcript that was already generated and cached on the device.
     */
    @Transaction
    open suspend fun replaceAll(transcripts: List<Transcript>) {
        deleteRemoteForEpisodes(transcripts.map { it.episodeUuid })
        insertAll(transcripts)
    }

    /**
     * Deletes the server transcripts of [episodeUuids], keeping the on-device ones. An episode's
     * download can go away while its transcript is still useful to read.
     */
    @Transaction
    open suspend fun deleteRemoteForEpisodes(episodeUuids: Collection<String>) {
        episodeUuids.chunked(AppDatabase.SQLITE_BIND_ARG_LIMIT).forEach { chunk ->
            deleteRemoteForEpisodesUnsafe(chunk)
        }
    }

    @Query("SELECT * FROM episode_transcript WHERE episode_uuid IS :episodeUuid")
    abstract fun observeTranscripts(episodeUuid: String): Flow<List<Transcript>>

    /** Insert or replace a single transcript without touching the episode's other transcripts. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(transcript: Transcript)

    /** Remove only an on-device transcript, leaving server-provided ones intact. */
    @Query("DELETE FROM episode_transcript WHERE episode_uuid IS :episodeUuid AND url IS :url AND is_generated IS 1")
    abstract suspend fun deleteGenerated(episodeUuid: String, url: String)
}
