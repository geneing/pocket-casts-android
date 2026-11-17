package au.com.shiftyjelly.pocketcasts.models.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import au.com.shiftyjelly.pocketcasts.models.to.DbAdSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
abstract class AdSegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertAll(segments: List<DbAdSegment>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSegments(segments: List<DbAdSegment>)

    @Query("DELETE FROM episode_ad_segments WHERE episode_uuid IS :episodeUuid")
    protected abstract suspend fun deleteForEpisode(episodeUuid: String)

    @Transaction
    open suspend fun replaceAll(episodeUuid: String, segments: List<DbAdSegment>) {
        deleteForEpisode(episodeUuid)
        val episodeSegments = segments.filter { it.episodeUuid == episodeUuid }
        if (episodeSegments.isNotEmpty()) {
            insertAll(episodeSegments)
        }
    }

    @Query("SELECT * FROM episode_ad_segments WHERE episode_uuid IS :episodeUuid ORDER BY start_time ASC")
    protected abstract fun observeRawSegmentsForEpisode(episodeUuid: String): Flow<List<DbAdSegment>>

    fun observeSegmentsForEpisode(episodeUuid: String) = observeRawSegmentsForEpisode(episodeUuid).distinctUntilChanged()

    @Query("SELECT * FROM episode_ad_segments WHERE episode_uuid IS :episodeUuid ORDER BY start_time ASC")
    abstract suspend fun findSegmentsForEpisode(episodeUuid: String): List<DbAdSegment>

    @Query("SELECT COUNT(*) FROM episode_ad_segments WHERE episode_uuid IS :episodeUuid")
    abstract suspend fun countForEpisode(episodeUuid: String): Int

    @Query(
        "UPDATE episode_ad_segments SET deselected = :deselected " +
            "WHERE episode_uuid IS :episodeUuid AND segment_index IS :segmentIndex",
    )
    abstract suspend fun setDeselected(episodeUuid: String, segmentIndex: Int, deselected: Boolean)

    @Query("DELETE FROM episode_ad_segments")
    abstract suspend fun deleteAll()
}
