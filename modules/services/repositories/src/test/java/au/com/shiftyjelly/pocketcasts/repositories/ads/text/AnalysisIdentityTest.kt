package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevAdModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnalysisIdentityTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sttVersion = "android.ondevice.speech/1"
    private val spanVersion = AnalysisIdentity.transcriptModelVersion("respan/span-02-20261015", JevAdModel.SPAN_02, 1)

    private fun state(
        complete: Boolean = true,
        signature: String = "1:1000",
        model: String = spanVersion,
        stt: String = sttVersion,
    ) = AdAnalysisState(
        episodeUuid = "episode",
        isComplete = complete,
        audioSignature = signature,
        modelVersion = model,
        sttVersion = stt,
    )

    private fun reusable(state: AdAnalysisState?, model: JevAdModel = JevAdModel.SPAN_02) = AnalysisIdentity.isReusableTranscript(state, "1:1000", sttVersion, model, 1)

    @Test
    fun `signature changes when the downloaded file changes`() {
        val file = folder.newFile("episode.mp3")
        file.writeBytes(ByteArray(16))
        file.setLastModified(1_000)
        val first = AnalysisIdentity.audioSignature(file)

        file.writeBytes(ByteArray(32))
        file.setLastModified(2_000)
        val second = AnalysisIdentity.audioSignature(file)

        assertNotEquals(first, second)
    }

    @Test
    fun `reuse requires a complete state for the same audio, model, prompt and recognizer`() {
        assertTrue(reusable(state()))
        assertFalse("different audio", reusable(state(signature = "2:2000")))
        assertFalse("different model", reusable(state(), JevAdModel.JEV_1_14))
        assertFalse("different stt", reusable(state(stt = "other/2")))
        assertFalse("incomplete", reusable(state(complete = false)))
        assertFalse("no state", reusable(null))
        assertFalse(
            "different prompt",
            AnalysisIdentity.isReusableTranscript(state(), "1:1000", sttVersion, JevAdModel.SPAN_02, 2),
        )
    }

    @Test
    fun `legacy rows with empty identities are never reused`() {
        assertFalse(reusable(state(signature = "", model = "", stt = "")))
    }

    @Test
    fun `audio-only analyses are not treated as transcript analyses`() {
        assertFalse(AnalysisIdentity.isTranscriptAnalysis(state(signature = "", model = "", stt = "")))
        assertFalse(AnalysisIdentity.isTranscriptAnalysis(null))
        assertTrue(AnalysisIdentity.isTranscriptAnalysis(state()))
    }

    @Test
    fun `file signature includes size and last modified`() {
        val file = folder.newFile("episode.mp3")
        file.writeBytes(ByteArray(10))
        file.setLastModified(42_000)
        assertEquals("10:42000", AnalysisIdentity.audioSignature(file))
    }

    @Test
    fun `a transcript identity matches the answering model by prefix and the prompt revision`() {
        val span = AnalysisIdentity.transcriptModelVersion("respan/span-02-20261015", JevAdModel.SPAN_02, 1)

        assertEquals("respan/span-02-20261015#prompt1", span)
        assertTrue(AnalysisIdentity.isTranscriptModelVersion(span, JevAdModel.SPAN_02, 1))
        assertFalse("other model", AnalysisIdentity.isTranscriptModelVersion(span, JevAdModel.JEV_1_14, 1))
        assertFalse("new prompt", AnalysisIdentity.isTranscriptModelVersion(span, JevAdModel.SPAN_02, 2))
        assertEquals(
            "typesafe/jev-1.14#prompt1",
            AnalysisIdentity.transcriptModelVersion(null, JevAdModel.JEV_1_14, 1),
        )
    }
}
