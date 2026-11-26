package au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The temporary audio a remote pass writes: the episode's fetched stream copy and the encoded chunk
 * it is about to upload.
 *
 * Both are large — a stream copy is the whole episode and a chunk is a few megabytes — and both are
 * disposable the moment they have been read, so this class is the only place that names them and the
 * only place that removes them. Every pass deletes what it finds for its own episode before it starts
 * and again when it ends, and each chunk is removed as soon as its bytes are in memory, so a process
 * killed mid-pass leaves at most one file per episode behind. Anything a kill leaves is removed by
 * the next pass (stale files) or by [au.com.shiftyjelly.pocketcasts.repositories.ads.text.TranscriptRetentionWorker]
 * at launch and once a day, so nothing waits on the cache being evicted.
 */
@Singleton
class RemoteAudioCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun directory(): File = File(context.cacheDir, DIRECTORY).apply { mkdirs() }

    fun file(episodeUuid: String, extension: String): File = File(directory(), "$episodeUuid.$extension")

    /** Removes every file this episode owns: its stream copy and any chunk a killed pass left. */
    fun deleteFor(episodeUuid: String) {
        val prefix = "$episodeUuid."
        directory().listFiles()?.forEach { file ->
            if (file.name.startsWith(prefix)) runCatching { file.delete() }
        }
    }

    /**
     * Removes files nothing can still be using: older than [maxAgeMs], which is far longer than one
     * pass takes, so a pass running in another coroutine (a test, a benchmark) is never disturbed.
     * Returns how many files were removed and how many bytes they held.
     */
    fun deleteStale(now: Long = System.currentTimeMillis(), maxAgeMs: Long = STALE_MS): Pair<Int, Long> {
        var removed = 0
        var bytes = 0L
        directory().listFiles()?.forEach { file ->
            val modified = file.lastModified()
            if (file.isFile && modified > 0L && modified < now - maxAgeMs) {
                val length = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) {
                    removed++
                    bytes += length
                }
            }
        }
        return removed to bytes
    }

    companion object {
        /** Where the temporary audio lives, under the app's cache directory. */
        const val DIRECTORY = "remote-asr-audio"

        /**
         * How long a file may sit untouched before a sweep removes it. A pass over a two hour
         * episode is a few minutes, so an hour cannot hit live work.
         */
        const val STALE_MS = 60L * 60 * 1000
    }
}
