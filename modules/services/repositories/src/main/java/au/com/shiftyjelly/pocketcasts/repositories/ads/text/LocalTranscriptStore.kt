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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Stores on-device transcripts as WebVTT and registers them with the existing transcript stack,
 * so the transcript screen and its tap-to-seek work without a server transcript.
 *
 * Writes happen repeatedly while a recognition pass is running, not once at the end: every
 * completed line replaces the file and emits on [updates], which is how a visible transcript tab
 * grows during the pass. The file lock keeps that rewrite from being read half-written by the
 * detection pass running alongside it.
 */
@Singleton
class LocalTranscriptStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transcriptDao: TranscriptDao,
) {
    private val fileLock = Mutex()
    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val updates: SharedFlow<String> = _updates.asSharedFlow()

    fun file(episodeUuid: String): File = File(dir(), "$episodeUuid.vtt")

    private fun completionFile(episodeUuid: String): File = File(dir(), "$episodeUuid.complete")

    fun localUrl(episodeUuid: String) = "$SCHEME$episodeUuid"

    suspend fun hasTranscript(episodeUuid: String): Boolean = withContext(Dispatchers.IO) { file(episodeUuid).exists() }

    /**
     * Records the engine version that recognised the whole episode. Without it a transcript is
     * assumed to be partial (or from an older pipeline) and is recognised again, which is what keeps
     * a pre-queue file with reconstructed timings out of the analysis.
     */
    suspend fun markComplete(episodeUuid: String, version: String) {
        withContext(Dispatchers.IO) {
            completionFile(episodeUuid).apply { parentFile?.mkdirs() }.writeText(version)
        }
    }

    suspend fun isComplete(episodeUuid: String, version: String): Boolean = withContext(Dispatchers.IO) {
        completionFile(episodeUuid).takeIf { it.exists() }?.readText() == version
    }

    /** The cues already recognised for the episode, in episode time; empty when there are none. */
    suspend fun readCues(episodeUuid: String): List<TranscriptCue> = fileLock.withLock {
        withContext(Dispatchers.IO) {
            val target = file(episodeUuid)
            if (!target.exists()) emptyList() else runCatching { parseCues(target.readText()) }.getOrDefault(emptyList())
        }
    }

    suspend fun save(episodeUuid: String, cues: List<TranscriptCue>) = saveProgress(episodeUuid, cues)

    /** Replaces the stored transcript with [cues] and wakes any screen that is showing it. */
    suspend fun saveProgress(episodeUuid: String, cues: List<TranscriptCue>) {
        fileLock.withLock {
            withContext(Dispatchers.IO) {
                writeAtomically(file(episodeUuid), toVtt(cues))
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
        _updates.tryEmit(episodeUuid)
    }

    /**
     * Writes [content] to [target] through a sibling temporary file, then renames it into place.
     *
     * Recognition rewrites the whole transcript after every completed line (every few seconds) while
     * the transcript tab is reading the same file. Writing in place truncates it first, and a reader
     * that opens the file in that window parses a half-written cue and throws — the tab then shows
     * "no transcript" for a transcript that exists, permanently for a finished episode because
     * nothing rewrites the file again. A rename inside one directory is atomic, so a reader sees
     * either the previous transcript or the new one, never a torn one.
     */
    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(content)
        if (temporary.renameTo(target)) return
        // renameTo replaces an existing file on Android's filesystems; this path is for the odd one
        // that refuses, and keeps the same guarantee by copying before removing the temporary.
        temporary.copyTo(target, overwrite = true)
        temporary.delete()
    }

    /**
     * Stores the on-device transcript as the episode's only one, dropping any transcript the show or
     * our servers published. Ad analysis and navigation are based on the recognized text only, so a
     * published transcript must not stay behind as an alternative reading of the same episode.
     */
    suspend fun replaceRemoteAndSave(episodeUuid: String, cues: List<TranscriptCue>) {
        withContext(Dispatchers.IO) {
            transcriptDao.deleteRemoteForEpisodes(listOf(episodeUuid))
        }
        saveProgress(episodeUuid, cues)
    }

    suspend fun delete(episodeUuid: String) {
        fileLock.withLock {
            withContext(Dispatchers.IO) {
                file(episodeUuid).delete()
                completionFile(episodeUuid).delete()
                transcriptDao.deleteGenerated(episodeUuid, localUrl(episodeUuid))
            }
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

    private fun parseCues(vtt: String): List<TranscriptCue> {
        val cues = ArrayList<TranscriptCue>()
        var pendingStart: Long? = null
        var pendingEnd: Long? = null
        for (raw in vtt.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line == "WEBVTT") continue
            val start = pendingStart
            if (start == null) {
                val times = TIMESTAMP_LINE.matchEntire(line) ?: continue
                pendingStart = parseTimestamp(times.groupValues[1])
                pendingEnd = parseTimestamp(times.groupValues[2])
                continue
            }
            val end = pendingEnd ?: start
            cues.add(TranscriptCue(startMs = start, endMs = end.coerceAtLeast(start), text = line))
            pendingStart = null
            pendingEnd = null
        }
        return cues
    }

    private fun parseTimestamp(value: String): Long {
        val parts = value.split(':')
        if (parts.size != 3) return 0L
        val secondsPart = parts[2].split('.')
        val hours = parts[0].toLongOrNull() ?: 0L
        val minutes = parts[1].toLongOrNull() ?: 0L
        val seconds = secondsPart.getOrNull(0)?.toLongOrNull() ?: 0L
        val millis = secondsPart.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
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

        private val TIMESTAMP_LINE = Regex("(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}) --> (\\d{2}:\\d{2}:\\d{2}\\.\\d{3})")
    }
}
