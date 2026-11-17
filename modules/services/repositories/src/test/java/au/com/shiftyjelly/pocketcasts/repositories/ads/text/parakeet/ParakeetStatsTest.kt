package au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParakeetStatsTest {
    @Test
    fun `rtf is audio time over wall time, so above one is faster than real time`() {
        assertEquals(2.0, ParakeetStats.rtf(60_000, 30_000), 0.0001)
        assertEquals(0.5, ParakeetStats.rtf(30_000, 60_000), 0.0001)
    }

    @Test
    fun `a pass that measured nothing reports zero instead of infinity`() {
        assertEquals(0.0, ParakeetStats.rtf(60_000, 0), 0.0)
        assertEquals(0.0, ParakeetStats.rtf(0, 30_000), 0.0)
        assertEquals(0.0, ParakeetStats.rtf(-1, 30_000), 0.0)
    }

    @Test
    fun `rtf is formatted with two decimals in the US locale`() {
        assertEquals("2.00", ParakeetStats.formatRtf(2.0))
        assertEquals("0.33", ParakeetStats.formatRtf(1.0 / 3.0))
    }

    @Test
    fun `the pass line carries every field as k equals v`() {
        val pass = ParakeetPass(
            modelLoadMs = 850,
            audioMs = 60_000,
            wallMs = 30_000,
            segments = 40,
            cues = 39,
            segmentMsMedian = 1_500,
        )

        assertEquals(
            "pass: model_load_ms=850 audio_ms=60000 wall_ms=30000 rtf=2.00 segments=40 cues=39 " +
                "segment_ms_median=1500",
            ParakeetStats.logLine(pass),
        )
    }

    @Test
    fun `download fields only appear when a download happened`() {
        val pass = ParakeetPass(
            modelLoadMs = 10,
            audioMs = 1_000,
            wallMs = 2_000,
            segments = 10,
            cues = 10,
            segmentMsMedian = 1_000,
            modelDownloadMs = 120_000,
            modelBytes = 614_588_752,
        )

        val line = ParakeetStats.logLine(pass)
        assertTrue(line.endsWith("model_download_ms=120000 model_bytes=614588752"))
        val withoutDownload = ParakeetStats.logLine(pass.copy(modelDownloadMs = null, modelBytes = null))
        assertFalse(withoutDownload.contains("model_download_ms"))
        assertFalse(withoutDownload.contains("model_bytes"))
    }

    @Test
    fun `the pass exposes the same ratio it logs`() {
        val pass = ParakeetPass(
            modelLoadMs = 0,
            audioMs = 60_000,
            wallMs = 20_000,
            segments = 1,
            cues = 1,
            segmentMsMedian = 60_000,
        )

        assertEquals(3.0, pass.rtf, 0.0001)
        assertTrue(ParakeetStats.logLine(pass).contains("rtf=3.00"))
    }

    @Test
    fun `the median is the upper middle value so it is always a measured segment`() {
        assertEquals(0L, ParakeetStats.median(emptyList()))
        assertEquals(1_000L, ParakeetStats.median(listOf(1_000L)))
        assertEquals(2_000L, ParakeetStats.median(listOf(3_000L, 1_000L, 2_000L)))
        // Even counts take the element at size / 2 rather than averaging the two middle values, so a
        // reported segment length is never a number the engine did not produce.
        assertEquals(3_000L, ParakeetStats.median(listOf(1_000L, 3_000L, 2_000L, 4_000L)))
    }

    @Test
    fun `percent is for the worker's progress data`() {
        assertEquals(0, ParakeetStats.percent(0, 0))
        assertEquals(50, ParakeetStats.percent(50, 100))
        assertEquals(100, ParakeetStats.percent(614_588_752, 614_588_752))
    }

    @Test
    fun `progress shows text, position, both clocks, the ratio so far and the segment count`() {
        val line = ParakeetStats.progress(
            chars = 1530,
            atMs = 3 * 60_000L + 5_000L,
            elapsedMs = 42_000L,
            fedAudioMs = 100_000L,
            segments = 12,
        )

        assertEquals("progress: chars=1530 at=3:05 elapsed=0:42 audio=1:40 rtf=2.38 segments=12", line)
    }

    @Test
    fun `durations grow to hours and never go negative`() {
        assertEquals("0:00", ParakeetStats.duration(0))
        assertEquals("0:59", ParakeetStats.duration(59_999))
        assertEquals("12:34", ParakeetStats.duration(12 * 60_000L + 34_000L))
        assertEquals("1:02:03", ParakeetStats.duration(3_723_000L))
        assertEquals("0:00", ParakeetStats.duration(-5_000L))
    }
}
