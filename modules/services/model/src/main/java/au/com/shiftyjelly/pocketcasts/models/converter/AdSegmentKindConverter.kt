package au.com.shiftyjelly.pocketcasts.models.converter

import androidx.room.TypeConverter
import au.com.shiftyjelly.pocketcasts.models.to.AdSegmentKind

class AdSegmentKindConverter {
    @TypeConverter
    fun fromInt(value: Int?): AdSegmentKind {
        return value?.let { AdSegmentKind.fromId(it) } ?: AdSegmentKind.Unknown
    }

    @TypeConverter
    fun toInt(value: AdSegmentKind?): Int {
        return value?.id ?: AdSegmentKind.Unknown.id
    }
}
