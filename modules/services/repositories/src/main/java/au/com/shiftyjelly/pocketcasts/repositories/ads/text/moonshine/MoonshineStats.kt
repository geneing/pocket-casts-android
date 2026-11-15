package au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine

import ai.moonshine.voice.TranscriptLine
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What one finished transcription pass cost and produced, logged as one line of `k=v` fields.
 *
 * Why log it at all: the only question worth answering from a bug report is whether the device is
 * slower than real time, and how well the words line up with the audio. `rtf` answers the first
 * (above 1 is faster than real time); the median line length, words per line and first-word offset
 * answer the second, so a bad decode is visible in the log instead of hiding in a score.
 */
data class MoonshinePass(
    val modelLoadMs: Long,
    val audioMs: Long,
    val wallMs: Long,
    val lineStats: MoonshineLineStats,
    val cues: Int,
    val modelDownloadMs: Long? = null,
    val modelBytes: Long? = null,
) {
    val rtf: Double get() = MoonshineStats.rtf(audioMs, wallMs)
}

/** Alignment diagnostics taken from the lines a pass produced. */
data class MoonshineLineStats(
    val lines: Int,
    val lineMsMedian: Long,
    val wordsPerLineMedian: Int,
    val firstWordOffsetMsMedian: Long,
)

/** Pure arithmetic and formatting for [MoonshinePass], so the numbers are unit-testable. */
object MoonshineStats {
    /** The `LogBuffer` tag every line uses, so one grep finds the whole history. */
    const val TAG = "MoonshineStt"

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

    fun logLine(pass: MoonshinePass): String = buildString {
        append("pass: ")
        append("model_load_ms=").append(pass.modelLoadMs)
        append(" audio_ms=").append(pass.audioMs)
        append(" wall_ms=").append(pass.wallMs)
        append(" rtf=").append(formatRtf(pass.rtf))
        append(" lines=").append(pass.lineStats.lines)
        append(" cues=").append(pass.cues)
        append(" line_ms_median=").append(pass.lineStats.lineMsMedian)
        append(" words_per_line_median=").append(pass.lineStats.wordsPerLineMedian)
        append(" first_word_offset_ms_median=").append(pass.lineStats.firstWordOffsetMsMedian)
        pass.modelDownloadMs?.let { append(" model_download_ms=").append(it) }
        pass.modelBytes?.let { append(" model_bytes=").append(it) }
    }

    /** What the pass produced, from the lines themselves rather than from the cues. */
    fun lineStats(lines: List<TranscriptLine>): MoonshineLineStats {
        val durationsMs = lines.map { (it.duration.toDouble() * 1000.0).roundToLong() }
        val wordsPerLine = lines.map { it.words?.size ?: 0 }
        val firstWordOffsetsMs = lines.mapNotNull { line ->
            val first = line.words?.firstOrNull() ?: return@mapNotNull null
            ((first.start - line.startTime).toDouble() * 1000.0).roundToLong()
        }
        return MoonshineLineStats(
            lines = lines.size,
            lineMsMedian = median(durationsMs),
            wordsPerLineMedian = median(wordsPerLine),
            firstWordOffsetMsMedian = median(firstWordOffsetsMs),
        )
    }

    /** Upper median: the element at the middle index of the sorted values, 0 when empty. */
    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    /** Upper median of the same shape, for counts. */
    fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    /** Truncates rather than rounds, which is enough for a log percentage. */
    fun percent(done: Long, total: Long): Int = if (total <= 0L) 0 else ((done.toDouble() / total) * 100.0).roundToInt()
}
