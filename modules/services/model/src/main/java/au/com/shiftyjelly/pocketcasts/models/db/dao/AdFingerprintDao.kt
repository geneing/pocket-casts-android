package au.com.shiftyjelly.pocketcasts.models.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import au.com.shiftyjelly.pocketcasts.models.to.AdFingerprint

@Dao
interface AdFingerprintDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fingerprint: AdFingerprint)

    @Query("SELECT * FROM ad_fingerprints WHERE podcast_uuid IS :podcastUuid")
    suspend fun findForPodcast(podcastUuid: String): List<AdFingerprint>

    @Query("SELECT COUNT(*) FROM ad_fingerprints")
    suspend fun count(): Int

    @Query("DELETE FROM ad_fingerprints WHERE podcast_uuid IS :podcastUuid")
    suspend fun deleteForPodcast(podcastUuid: String)
}
