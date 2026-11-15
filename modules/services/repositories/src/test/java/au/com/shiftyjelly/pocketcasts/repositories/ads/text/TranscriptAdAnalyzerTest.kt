package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.models.to.Transcript
import au.com.shiftyjelly.pocketcasts.models.to.TranscriptEntry
import au.com.shiftyjelly.pocketcasts.models.to.TranscriptType
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptAdAnalyzerTest {
    private fun transcript(
        url: String = "https://shownotes.pocketcasts.com/generated_transcripts/podcast/episode.vtt",
        vararg entries: TranscriptEntry,
    ) = Transcript.Text(
        entries = entries.toList(),
        type = TranscriptType.Vtt,
        url = url,
        isGenerated = true,
        episodeUuid = "episode-uuid",
        podcastUuid = "podcast-uuid",
    )

    @Test
    fun `timed text entries become cues and speakers and unusable entries are dropped`() {
        val cues = transcript(
            "url",
            TranscriptEntry.Speaker("Host"),
            TranscriptEntry.Text("this episode is brought to you by acme", startTimeMs = 60_000, endTimeMs = 63_000),
            TranscriptEntry.Text("  ", startTimeMs = 64_000, endTimeMs = 65_000),
            TranscriptEntry.Text("no timing"),
            TranscriptEntry.Text("use code save", startTimeMs = 63_000, endTimeMs = 60_000),
        ).toAnalysisCues()

        assertEquals(
            listOf(
                TranscriptCue(60_000, 63_000, "this episode is brought to you by acme"),
                TranscriptCue(63_000, 63_000, "use code save"),
            ),
            cues,
        )
    }

    @Test
    fun `a fetched transcript is identified by its url`() {
        val version = transcript("https://example.com/episode.vtt").analysisVersion("mlkit")
        assertEquals("transcript:https://example.com/episode.vtt", version)
    }

    @Test
    fun `an on-device transcript is identified by the recognizer`() {
        val version = transcript("${LocalTranscriptStore.SCHEME}episode-uuid").analysisVersion("mlkit")
        assertEquals("mlkit", version)
    }
}
