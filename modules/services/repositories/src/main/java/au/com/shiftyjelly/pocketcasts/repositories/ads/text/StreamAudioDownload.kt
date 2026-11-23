package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import java.io.File
import java.io.IOException
import okhttp3.Call
import okhttp3.Request

/**
 * Downloads a streamed episode to [target] so the on-device recognizer and the speech profiler can
 * open it as a local file.
 *
 * ML Kit's `AudioSource.fromUri` resolves its URI through a ContentResolver, which knows nothing
 * about `https`, so a stream URL can never be transcribed in place. Fetching the bytes once also
 * lets the profiler measure the same audio the recognizer heard, instead of falling back to
 * spreading the transcript over the episode duration.
 *
 * Returns [target] when the whole body was written, or `null` on any HTTP or transport failure. A
 * partial file is deleted so a retry never reads a truncated episode.
 */
internal fun downloadAudioStream(
    client: Call.Factory,
    url: String,
    target: File,
): File? {
    val request = Request.Builder().url(url).get().build()
    return try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                LogBuffer.e(TAG, "Stream fetch for $url returned HTTP ${response.code}")
                return null
            }
            val body = response.body
            target.outputStream().use { output -> body.byteStream().copyTo(output) }
            target.takeIf { it.length() > 0L }
        }
    } catch (e: IOException) {
        LogBuffer.e(TAG, e, "Could not fetch stream audio from $url")
        runCatching { target.delete() }
        null
    }
}

private const val TAG = "AdStreamDownload"
