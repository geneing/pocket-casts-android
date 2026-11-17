package au.com.shiftyjelly.pocketcasts.models.to

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Compact spectral signature of a segment that was previously treated as an ad, used to recognise
 * the same creative in later episodes. Stored per podcast so matching stays local and cheap.
 */
@Entity(
    tableName = "ad_fingerprints",
    indices = [
        Index(name = "ad_fingerprint_podcast_uuid_index", value = ["podcast_uuid"]),
    ],
)
class AdFingerprint(
    @PrimaryKey @ColumnInfo(name = "fingerprint") val fingerprint: String,
    @ColumnInfo(name = "podcast_uuid") val podcastUuid: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "signature") val signature: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
