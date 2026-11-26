package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import au.com.shiftyjelly.pocketcasts.models.db.dao.TranscriptDao
import au.com.shiftyjelly.pocketcasts.models.entity.Transcript
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
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

    private fun audioFile(episodeUuid: String): File = File(dir(), "$episodeUuid.audio")

    private fun engineFile(episodeUuid: String): File = File(dir(), "$episodeUuid.engine")

    private fun costFile(episodeUuid: String): File = File(dir(), "$episodeUuid.cost")

    fun localUrl(episodeUuid: String) = "$SCHEME$episodeUuid"

    suspend fun hasTranscript(episodeUuid: String): Boolean = withContext(Dispatchers.IO) { file(episodeUuid).exists() }

    /** Reads a transcript for a screen or chat and records when it was last used. */
    suspend fun readForDisplay(episodeUuid: String): String? = fileLock.withLock {
        withContext(Dispatchers.IO) {
            val target = file(episodeUuid)
            if (!target.isFile) return@withContext null
            val contents = target.readText()
            if (!target.setLastModified(System.currentTimeMillis())) {
                throw IOException("Could not update transcript access time for $episodeUuid")
            }
            contents
        }
    }

    suspend fun idleTranscriptUuids(cutoffMs: Long): List<String> = fileLock.withLock {
        withContext(Dispatchers.IO) {
            dir().listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".vtt") && it.lastModified() in 1..cutoffMs }
                .map { it.name.removeSuffix(".vtt") }
        }
    }

    /** Rechecks the access time while holding the file lock, before removing the transcript. */
    suspend fun deleteIfIdle(episodeUuid: String, cutoffMs: Long, beforeDelete: suspend () -> Boolean): Boolean = fileLock.withLock {
        withContext(Dispatchers.IO) {
            val target = file(episodeUuid)
            if (!target.isFile || target.lastModified() !in 1..cutoffMs) return@withContext false
            if (!beforeDelete()) return@withContext false
            deleteFilesAndRow(episodeUuid)
            true
        }
    }.also { deleted -> if (deleted) _updates.tryEmit(episodeUuid) }

    /**
     * Records the audio a recognition pass is about to read, before it reads any of it.
     *
     * Cues are written while the pass runs, so a process killed mid-pass leaves a partial transcript
     * behind. Recording the audio first is what lets the next pass tell whether it is resuming over
     * the same copy of the episode: a download that landed in between, or a stream the CDN stitched
     * with different ads, has moved every cue that was written from the other copy.
     */
    suspend fun recordAudio(episodeUuid: String, audioSignature: String) {
        withContext(Dispatchers.IO) {
            audioFile(episodeUuid).apply { parentFile?.mkdirs() }.writeText(audioSignature)
        }
    }

    /**
     * Records the engine a recognition pass is about to use, before it writes any cue.
     *
     * Cues are written while the pass runs, so an engine that changes between passes — the dev
     * toggle, or remote transcription falling back to the device — must not resume a half-written
     * transcript: two engines place the same word differently and the cues would not join. The
     * sidecar is what makes the next pass able to see that and start over.
     */
    suspend fun recordEngine(episodeUuid: String, version: String) {
        withContext(Dispatchers.IO) {
            engineFile(episodeUuid).apply { parentFile?.mkdirs() }.writeText(version)
        }
    }

    /**
     * The engine that produced the cues stored for the episode, whether the pass finished or not:
     * the completion marker for a whole transcript, the pass sidecar otherwise. Null when nothing
     * recorded it, which is a transcript from before the sidecar existed.
     */
    suspend fun storedEngine(episodeUuid: String): String? = withContext(Dispatchers.IO) {
        readCompletion(episodeUuid)?.first?.takeIf { it.isNotBlank() } ?: passEngine(episodeUuid)
    }

    private fun passEngine(episodeUuid: String): String? = engineFile(episodeUuid).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Records the engine version that recognised the whole episode, and the audio it recognised.
     *
     * [audioSignature] is what makes the marker answer "does this transcript belong to the audio
     * playing now?": the stream and the download of one episode can carry different ad stitches, and
     * cue times do not survive the switch. When it is not given, the audio recorded by [recordAudio]
     * is kept, so a marker written for cues collected across several passes names the audio they
     * were actually read from rather than the audio that happens to be current.
     */
    suspend fun markComplete(episodeUuid: String, version: String, audioSignature: String? = null) {
        withContext(Dispatchers.IO) {
            val audio = audioSignature?.takeIf { it.isNotBlank() }
                ?: passAudio(episodeUuid)
                ?: readCompletion(episodeUuid)?.second?.takeIf { it.isNotBlank() }
            val text = if (audio == null) version else "$version\n$audio"
            completionFile(episodeUuid).apply { parentFile?.mkdirs() }.writeText(text)
        }
        _updates.tryEmit(episodeUuid)
    }

    suspend fun isComplete(episodeUuid: String, version: String): Boolean = withContext(Dispatchers.IO) {
        readCompletion(episodeUuid)?.first == version
    }

    /**
     * The audio this transcript was recognised from, or null when nothing recorded it and the
     * transcript cannot say which copy of the episode it heard.
     */
    suspend fun recognisedAudio(episodeUuid: String): String? = withContext(Dispatchers.IO) {
        readCompletion(episodeUuid)?.second?.takeIf { it.isNotBlank() } ?: passAudio(episodeUuid)
    }

    /** The audio the last pass read, recorded before its first window. */
    private fun passAudio(episodeUuid: String): String? = audioFile(episodeUuid).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Adds one request's price to what this episode has cost so far.
     *
     * A remote pass bills per chunk and a resumed pass bills again, so the total is accumulated
     * rather than reported per pass. The sidecar is appended to, never rewritten: two passes never
     * race on it, and a half-written line is skipped by [totalCost] instead of failing the pass.
     */
    suspend fun addCost(episodeUuid: String, usd: Double) {
        if (usd <= 0.0) return
        withContext(Dispatchers.IO) {
            costFile(episodeUuid).apply { parentFile?.mkdirs() }.appendText("$usd\n")
        }
    }

    /** The price of every remote request this transcript has been billed for, or null if none. */
    suspend fun totalCost(episodeUuid: String): Double? = withContext(Dispatchers.IO) {
        costFile(episodeUuid).takeIf { it.isFile }?.readLines()
            ?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?.sum()
    }

    /**
     * Appends the price of the remote pass to the end of the transcript, where a listener can see
     * it. The on-device engine records no cost, so this is a no-op there.
     *
     * The line is a cue rather than a WebVTT `NOTE` because the transcript tab renders cues and
     * silently drops notes, and "show the cost at the end of the transcript" is exactly what a cue
     * at the end shows. It starts after the last cue so it never competes with speech for a timestamp.
     */
    suspend fun appendCostSummary(episodeUuid: String) {
        val usd = totalCost(episodeUuid) ?: return
        val cues = readCues(episodeUuid)
        if (cues.any { it.text.startsWith(COST_PREFIX) }) return
        val startMs = (cues.lastOrNull()?.endMs ?: 0L) + COST_CUE_GAP_MS
        val label = String.format(Locale.US, "\$%.4f", usd)
        saveProgress(episodeUuid, cues + TranscriptCue(startMs, startMs + COST_CUE_MS, "$COST_PREFIX $label"))
    }

    private fun readCompletion(episodeUuid: String): Pair<String, String?>? = completionFile(episodeUuid).takeIf { it.exists() }?.let { file ->
        val lines = file.readText().lineSequence().toList()
        lines.firstOrNull()?.trim().orEmpty() to lines.getOrNull(1)?.trim()
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
     * Recognition rewrites the whole transcript after every completed segment (every few seconds)
     * while the transcript tab is reading the same file. Writing in place truncates it first, and a
     * reader that opens the file in that window parses a half-written cue and throws — the tab then
     * shows "no transcript" for a transcript that exists, permanently for a finished episode because
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

    suspend fun delete(episodeUuid: String) {
        fileLock.withLock {
            withContext(Dispatchers.IO) {
                deleteFilesAndRow(episodeUuid)
            }
        }
        _updates.tryEmit(episodeUuid)
    }

    private suspend fun deleteFilesAndRow(episodeUuid: String) {
        val target = file(episodeUuid)
        // Keep the VTT until the sidecars are gone, so a failed cleanup is discovered on the next sweep.
        listOf(
            completionFile(episodeUuid),
            audioFile(episodeUuid),
            engineFile(episodeUuid),
            costFile(episodeUuid),
            File(target.parentFile, "${target.name}.tmp"),
            target,
        )
            .forEach { item ->
                if (item.exists() && !item.delete()) throw IOException("Could not delete ${item.name}")
            }
        transcriptDao.deleteGenerated(episodeUuid, localUrl(episodeUuid))
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

        /** How a remote pass's price is written at the end of the transcript. */
        const val COST_PREFIX = "Remote transcription cost:"

        private const val COST_CUE_GAP_MS = 1_000L
        private const val COST_CUE_MS = 2_000L

        private val TIMESTAMP_LINE = Regex("(\\d{2}:\\d{2}:\\d{2}\\.\\d{3}) --> (\\d{2}:\\d{2}:\\d{2}\\.\\d{3})")
    }
}
