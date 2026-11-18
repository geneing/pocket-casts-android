package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet

import java.util.Locale
import kotlin.math.roundToInt

/**
 * What one transcription pass measured.
 *
 * Split out of the transcriber so the numbers can be logged and unit-tested without LiteRT, an
 * audio file or a model. [segmentMsMedian] is the number to read first when cues look wrong: it is
 * how long the engine's own pieces are — words here, not encoder windows — and therefore how much of
 * the transcript's shape comes from the recogniser rather than from the cue rules.
 */
data class ParakeetPass(
    val modelLoadMs: Long,
    val audioMs: Long,
    val wallMs: Long,
    val segments: Int,
    val cues: Int,
    val segmentMsMedian: Long,
    val modelDownloadMs: Long? = null,
    val modelBytes: Long? = null,
    /**
     * Which accelerator ran the pass. The three are far apart on speed for this model - the TDT
     * decoder is dozens of single-token invocations per window - so a number without it says
     * nothing about the device.
     */
    val backend: String? = null,
    /** Windows the encoder ran; each carries 5 s of audio and steps 3 s. */
    val windows: Int? = null,
    /** Wall time in the mel front end, the encoder and the TDT decoder, summed over the windows. */
    val melMs: Long? = null,
    val encodeMs: Long? = null,
    val decodeMs: Long? = null,
) {
    val rtf: Double get() = ParakeetStats.rtf(audioMs, wallMs)

    /** Audio time over the time the model itself took, without the CPU-only front end. */
    val modelRtf: Double get() = when {
        encodeMs == null || decodeMs == null -> 0.0
        else -> ParakeetStats.rtf(audioMs, encodeMs + decodeMs)
    }
}

/** Pure arithmetic and formatting for [ParakeetPass], so the numbers are unit-testable. */
object ParakeetStats {
    /** The `LogBuffer` tag every line uses, so one grep finds the whole history. */
    const val TAG = "ParakeetStt"

    /**
     * Audio time divided by wall time. Above 1 means the pass ran faster than real time; a missing
     * or non-positive wall time reports 0 instead of infinity, because a zero-length pass measured
     * nothing.
     */
    fun rtf(audioMs: Long, wallMs: Long): Double = when {
        audioMs <= 0L || wallMs <= 0L -> 0.0
        else -> audioMs.toDouble() / wallMs
    }

    /** Two decimals, US locale, so log scraping and comparisons do not depend on the device locale. */
    fun formatRtf(rtf: Double): String = String.format(Locale.US, "%.2f", rtf)

    /**
     * How far a download or a pass has got, for the worker's progress data. An unknown total reports
     * 0 rather than dividing by zero; the caller shows an indeterminate bar for that case.
     */
    fun percent(done: Long, total: Long): Int = when {
        total <= 0L -> 0
        else -> ((done.toDouble() / total) * 100.0).roundToInt()
    }

    /**
     * `mm:ss`, or `h:mm:ss` past an hour, for the two clocks a progress line shows. Episode audio
     * and elapsed wall time are read side by side, so one format for both keeps the line scannable.
     */
    fun duration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * Upper median: the element at the middle index of the sorted values, 0 when empty.
     *
     * The upper median rather than an average of the two middle values because it is always one of
     * the measured segments, so it cannot report a segment length the engine never produced.
     */
    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    /**
     * One line while a pass runs.
     *
     * The recogniser reports words as they become final and there is no other way to see that a long
     * pass is moving: [chars] is how much text has been produced, [atMs] where the newest word ends,
     * [elapsedMs] how long the pass has been running, [fedAudioMs] how much audio has been handed
     * over, and [segments] how many final segments have arrived. The two clocks side by side turn
     * the line into a live speed reading.
     */
    fun progress(
        chars: Int,
        atMs: Long,
        elapsedMs: Long,
        fedAudioMs: Long,
        segments: Int,
    ): String = buildString {
        append("progress: ")
        append("chars=").append(chars)
        append(" at=").append(duration(atMs))
        append(" elapsed=").append(duration(elapsedMs))
        append(" audio=").append(duration(fedAudioMs))
        append(" rtf=").append(formatRtf(rtf(fedAudioMs, elapsedMs)))
        append(" segments=").append(segments)
    }

    /** The one-line summary of a finished pass; field names are kept stable so logs can be grepped. */
    fun logLine(pass: ParakeetPass): String = buildString {
        append("pass: ")
        append("model_load_ms=").append(pass.modelLoadMs)
        append(" audio_ms=").append(pass.audioMs)
        append(" wall_ms=").append(pass.wallMs)
        append(" rtf=").append(formatRtf(pass.rtf))
        append(" segments=").append(pass.segments)
        append(" cues=").append(pass.cues)
        append(" segment_ms_median=").append(pass.segmentMsMedian)
        pass.backend?.let { append(" backend=").append(it) }
        pass.windows?.let { append(" windows=").append(it) }
        pass.melMs?.let { append(" mel_ms=").append(it) }
        pass.encodeMs?.let { append(" encode_ms=").append(it) }
        pass.decodeMs?.let {
            append(" decode_ms=").append(it)
            append(" model_rtf=").append(formatRtf(pass.modelRtf))
        }
        pass.modelDownloadMs?.let { append(" model_download_ms=").append(it) }
        pass.modelBytes?.let { append(" model_bytes=").append(it) }
    }
}
