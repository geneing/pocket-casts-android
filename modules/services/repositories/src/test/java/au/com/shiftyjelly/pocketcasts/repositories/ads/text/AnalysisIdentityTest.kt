package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState
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

    private fun state(
        complete: Boolean = true,
        signature: String = "1:1000",
        model: String = "tfidf-ad-v1:abc",
        stt: String = "android.ondevice.speech/1",
    ) = AdAnalysisState(
        episodeUuid = "episode",
        isComplete = complete,
        audioSignature = signature,
        modelVersion = model,
        sttVersion = stt,
    )

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
    fun `reuse requires a complete state for the same audio, model and recognizer`() {
        assertTrue(AnalysisIdentity.isReusable(state(), "1:1000", "tfidf-ad-v1:abc", "android.ondevice.speech/1"))
        assertFalse("different audio", AnalysisIdentity.isReusable(state(), "2:2000", "tfidf-ad-v1:abc", "android.ondevice.speech/1"))
        assertFalse("different model", AnalysisIdentity.isReusable(state(), "1:1000", "tfidf-ad-v2:def", "android.ondevice.speech/1"))
        assertFalse("different stt", AnalysisIdentity.isReusable(state(), "1:1000", "tfidf-ad-v1:abc", "other/2"))
        assertFalse("incomplete", AnalysisIdentity.isReusable(state(complete = false), "1:1000", "tfidf-ad-v1:abc", "android.ondevice.speech/1"))
        assertFalse("no state", AnalysisIdentity.isReusable(null, "1:1000", "tfidf-ad-v1:abc", "android.ondevice.speech/1"))
    }

    @Test
    fun `legacy rows with empty identities are never reused`() {
        assertFalse(AnalysisIdentity.isReusable(state(signature = "", model = "", stt = ""), "1:1000", "tfidf-ad-v1:abc", "android.ondevice.speech/1"))
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
}
