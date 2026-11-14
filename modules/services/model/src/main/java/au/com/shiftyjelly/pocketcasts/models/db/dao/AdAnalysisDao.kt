package au.com.shiftyjelly.pocketcasts.models.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState

@Dao
interface AdAnalysisDao {
    @Query("SELECT * FROM episode_ad_analysis WHERE episode_uuid IS :episodeUuid")
    suspend fun findState(episodeUuid: String): AdAnalysisState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: AdAnalysisState)

    @Query("DELETE FROM episode_ad_analysis WHERE episode_uuid IS :episodeUuid")
    suspend fun delete(episodeUuid: String)
}
