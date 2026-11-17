package au.com.shiftyjelly.pocketcasts.models.to

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Progress of the on-device ad analysis for one episode, so partial passes can resume.
 *
 * [audioSignature], [modelVersion] and [sttVersion] pin the result to the exact downloaded file
 * and inference stack. A different file, model bundle or recognizer invalidates the analysis
 * instead of serving stale segments.
 */
@Entity(tableName = "episode_ad_analysis")
data class AdAnalysisState(
    @PrimaryKey @ColumnInfo(name = "episode_uuid") val episodeUuid: String,
    @ColumnInfo(name = "analyzed_until_ms") val analyzedUntilMs: Long = 0L,
    @ColumnInfo(name = "is_complete", defaultValue = "0") val isComplete: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
    @ColumnInfo(name = "audio_signature", defaultValue = "") val audioSignature: String = "",
    @ColumnInfo(name = "model_version", defaultValue = "") val modelVersion: String = "",
    @ColumnInfo(name = "stt_version", defaultValue = "") val sttVersion: String = "",
)
