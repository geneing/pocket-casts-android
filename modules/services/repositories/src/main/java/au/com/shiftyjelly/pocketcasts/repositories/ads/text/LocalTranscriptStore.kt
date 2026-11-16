package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import au.com.shiftyjelly.pocketcasts.models.db.dao.TranscriptDao
import au.com.shiftyjelly.pocketcasts.models.entity.Transcript
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores on-device transcripts as WebVTT and registers them with the existing transcript stack,
 * so the transcript screen and its tap-to-seek work without a server transcript.
 */
@Singleton
class LocalTranscriptStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transcriptDao: TranscriptDao,
) {
    fun file(episodeUuid: String): File = File(dir(), "$episodeUuid.vtt")

    fun localUrl(episodeUuid: String) = "$SCHEME$episodeUuid"

    suspend fun hasTranscript(episodeUuid: String): Boolean = withContext(Dispatchers.IO) { file(episodeUuid).exists() }

    suspend fun save(episodeUuid: String, cues: List<TranscriptCue>) {
        withContext(Dispatchers.IO) {
            val target = file(episodeUuid)
            target.parentFile?.mkdirs()
            target.writeText(toVtt(cues))
            transcriptDao.upsert(
                Transcript(
                    episodeUuid = episodeUuid,
                    url = localUrl(episodeUuid),
                    type = "text/vtt",
                    isGenerated = true,
                    language = Locale.getDefault().language,
                ),
            )
        }
    }

    /**
     * Stores the on-device transcript as the episode's only one, dropping any transcript the show or
     * our servers published. Only the debug switch that forces on-device recognition uses this: the
     * published transcript is re-downloadable, and leaving it behind would keep the transcript tab on
     * the text the analysis was told to ignore.
     */
    suspend fun replaceRemoteAndSave(episodeUuid: String, cues: List<TranscriptCue>) {
        withContext(Dispatchers.IO) {
            transcriptDao.deleteRemoteForEpisodes(listOf(episodeUuid))
        }
        save(episodeUuid, cues)
    }

    suspend fun delete(episodeUuid: String) {
        withContext(Dispatchers.IO) {
            file(episodeUuid).delete()
            transcriptDao.deleteGenerated(episodeUuid, localUrl(episodeUuid))
        }
    }

    private fun dir(): File = File(context.filesDir, "on-device-transcripts").apply { mkdirs() }

    private fun toVtt(cues: List<TranscriptCue>): String = buildString {
        appendLine("WEBVTT")
        appendLine()
        for (cue in cues.sortedBy { it.startMs }) {
            appendLine("${timestamp(cue.startMs)} --> ${timestamp(cue.endMs)}")
            appendLine(cue.text.replace("\n", " "))
            appendLine()
        }
    }

    private fun timestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val hours = safe / 3_600_000
        val minutes = (safe % 3_600_000) / 60_000
        val seconds = (safe % 60_000) / 1000
        val millis = safe % 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    companion object {
        const val SCHEME = "local-generated://"
    }
}
