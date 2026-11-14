package au.com.shiftyjelly.pocketcasts.models.to

enum class AdSegmentKind(val id: Int) {
    Unknown(0),
    Ad(1),
    Sponsor(2),
    SelfPromo(3),
    ;

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: Unknown
    }
}
