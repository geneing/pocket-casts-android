package au.com.shiftyjelly.pocketcasts.models.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import au.com.shiftyjelly.pocketcasts.models.to.DbAdCueScore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
abstract class AdCueScoreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertAll(scores: List<DbAdCueScore>)

    @Query("DELETE FROM episode_ad_cue_scores WHERE episode_uuid IS :episodeUuid")
    protected abstract suspend fun deleteForEpisode(episodeUuid: String)

    @Transaction
    open suspend fun replaceAll(episodeUuid: String, scores: List<DbAdCueScore>) {
        deleteForEpisode(episodeUuid)
        val episodeScores = scores.filter { it.episodeUuid == episodeUuid }
        if (episodeScores.isNotEmpty()) {
            insertAll(episodeScores)
        }
    }

    @Query("SELECT * FROM episode_ad_cue_scores WHERE episode_uuid IS :episodeUuid ORDER BY cue_index ASC")
    protected abstract fun observeRawScoresForEpisode(episodeUuid: String): Flow<List<DbAdCueScore>>

    @Query("SELECT * FROM episode_ad_cue_scores WHERE episode_uuid IS :episodeUuid ORDER BY cue_index ASC")
    abstract suspend fun findScoresForEpisode(episodeUuid: String): List<DbAdCueScore>

    fun observeScoresForEpisode(episodeUuid: String) = observeRawScoresForEpisode(episodeUuid).distinctUntilChanged()

    @Query("SELECT COUNT(*) FROM episode_ad_cue_scores WHERE episode_uuid IS :episodeUuid")
    abstract suspend fun countForEpisode(episodeUuid: String): Int

    @Query("DELETE FROM episode_ad_cue_scores")
    abstract suspend fun deleteAll()
}
