package au.com.shiftyjelly.pocketcasts.repositories.ads.text.moonshine

import ai.moonshine.voice.TranscriptLine
import ai.moonshine.voice.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineStatsTest {
    @Test
    fun `rtf is audio time over wall time, so above one is faster than real time`() {
        assertEquals(2.0, MoonshineStats.rtf(60_000, 30_000), 0.0001)
        assertEquals(0.5, MoonshineStats.rtf(30_000, 60_000), 0.0001)
    }

    @Test
    fun `a pass that measured nothing reports zero instead of infinity`() {
        assertEquals(0.0, MoonshineStats.rtf(60_000, 0), 0.0)
        assertEquals(0.0, MoonshineStats.rtf(0, 30_000), 0.0)
        assertEquals(0.0, MoonshineStats.rtf(-1, 30_000), 0.0)
    }

    @Test
    fun `rtf is formatted with two decimals in the US locale`() {
        assertEquals("2.00", MoonshineStats.formatRtf(2.0))
        assertEquals("0.33", MoonshineStats.formatRtf(1.0 / 3.0))
    }

    @Test
    fun `the log line carries every field as k equals v`() {
        val pass = MoonshinePass(
            modelLoadMs = 850,
            audioMs = 60_000,
            wallMs = 30_000,
            lineStats = MoonshineLineStats(
                lines = 40,
                lineMsMedian = 1_500,
                wordsPerLineMedian = 9,
                firstWordOffsetMsMedian = 120,
            ),
            cues = 39,
        )

        assertEquals(
            "pass: model_load_ms=850 audio_ms=60000 wall_ms=30000 rtf=2.00 lines=40 cues=39 " +
                "line_ms_median=1500 words_per_line_median=9 first_word_offset_ms_median=120",
            MoonshineStats.logLine(pass),
        )
    }

    @Test
    fun `download fields only appear when a download happened`() {
        val pass = MoonshinePass(
            modelLoadMs = 10,
            audioMs = 1_000,
            wallMs = 2_000,
            lineStats = MoonshineLineStats(1, 1_000, 4, 0),
            cues = 1,
            modelDownloadMs = 120_000,
            modelBytes = 45_409_484,
        )

        val line = MoonshineStats.logLine(pass)
        assertTrue(line.contains("model_download_ms=120000"))
        assertTrue(line.contains("model_bytes=45409484"))
        val withoutDownload = MoonshineStats.logLine(pass.copy(modelDownloadMs = null, modelBytes = null))
        assertFalse(withoutDownload.contains("model_download_ms"))
        assertFalse(withoutDownload.contains("model_bytes"))
    }

    @Test
    fun `line stats describe alignment, not just counts`() {
        val lines = listOf(
            line(startTime = 0f, duration = 2f, wordStart = 0.2f, words = 3),
            line(startTime = 2f, duration = 4f, wordStart = 2.6f, words = 7),
            line(startTime = 6f, duration = 3f, wordStart = 6.1f, words = 5),
        )

        val stats = MoonshineStats.lineStats(lines)

        assertEquals(3, stats.lines)
        // Upper median of 2000, 4000, 3000.
        assertEquals(3_000, stats.lineMsMedian)
        assertEquals(5, stats.wordsPerLineMedian)
        // Offsets 200, 600, 100: median 200.
        assertEquals(200, stats.firstWordOffsetMsMedian)
    }

    @Test
    fun `lines without words contribute a zero offset but still count`() {
        val stats = MoonshineStats.lineStats(listOf(line(startTime = 0f, duration = 1f, wordStart = 0f, words = 0)))

        assertEquals(1, stats.lines)
        assertEquals(0, stats.wordsPerLineMedian)
        assertEquals(0, stats.firstWordOffsetMsMedian)
    }

    @Test
    fun `percent is for the worker's progress data`() {
        assertEquals(0, MoonshineStats.percent(0, 0))
        assertEquals(50, MoonshineStats.percent(50, 100))
        assertEquals(100, MoonshineStats.percent(100, 100))
    }

    private fun line(startTime: Float, duration: Float, wordStart: Float, words: Int): TranscriptLine = TranscriptLine().apply {
        this.text = "line"
        this.startTime = startTime
        this.duration = duration
        this.isComplete = true
        this.words = List(words) { index ->
            WordTiming().apply {
                this.word = "w$index"
                this.start = wordStart + index * 0.1f
                this.end = wordStart + (index + 1) * 0.1f
                this.confidence = 1f
            }
        }
    }
}
