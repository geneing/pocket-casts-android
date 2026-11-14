package au.com.shiftyjelly.pocketcasts.models.converter

import androidx.room.TypeConverter
import au.com.shiftyjelly.pocketcasts.models.to.AdSegmentOrigin

class AdSegmentOriginConverter {
    @TypeConverter
    fun fromInt(value: Int?): AdSegmentOrigin {
        return value?.let { AdSegmentOrigin.fromId(it) } ?: AdSegmentOrigin.Unknown
    }

    @TypeConverter
    fun toInt(value: AdSegmentOrigin?): Int {
        return value?.id ?: AdSegmentOrigin.Unknown.id
    }
}
