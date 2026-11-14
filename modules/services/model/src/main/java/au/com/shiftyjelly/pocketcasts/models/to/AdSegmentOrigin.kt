package au.com.shiftyjelly.pocketcasts.models.to

enum class AdSegmentOrigin(val id: Int) {
    Unknown(0),
    Server(1),
    Transcript(2),
    AudioAnalysis(3),
    RepeatMatch(4),
    ;

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: Unknown
    }
}
