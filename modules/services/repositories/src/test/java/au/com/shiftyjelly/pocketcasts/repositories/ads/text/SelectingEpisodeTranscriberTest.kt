package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import android.content.Context
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.ParakeetEpisodeTranscriber
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote.RemoteAsrHealth
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote.RemoteEpisodeTranscriber
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote.RemoteSttFailureKind
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.utils.featureflag.ModifiableFeatureProvider
import au.com.shiftyjelly.pocketcasts.utils.featureflag.ReleaseVersion
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SelectingEpisodeTranscriberTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val parakeet = mock<ParakeetEpisodeTranscriber>()
    private val remote = mock<RemoteEpisodeTranscriber>()
    private lateinit var health: RemoteAsrHealth
    private lateinit var provider: FakeProvider

    @Before
    fun setUp() {
        context.getSharedPreferences("POCKETCASTS_REMOTE_ASR", Context.MODE_PRIVATE).edit().clear().commit()
        FeatureFlag.clearProviders()
        provider = FakeProvider()
        FeatureFlag.initialize(listOf(provider))
        whenever(parakeet.version) doReturn ON_DEVICE_VERSION
        whenever(parakeet.isAvailable()) doReturn true
        whenever(remote.version) doReturn REMOTE_VERSION
        whenever(remote.isAvailable()) doReturn true
        health = RemoteAsrHealth(context)
    }

    @After
    fun tearDown() {
        FeatureFlag.clearProviders()
    }

    @Test
    fun `the remote engine is used while the flag is on and it is working`() {
        provider.setEnabled(Feature.REMOTE_TRANSCRIPTION, true)

        val transcriber = transcriber()

        assertTrue(transcriber.isRemoteActive())
        assertEquals(REMOTE_VERSION, transcriber.version)
    }

    @Test
    fun `a suspended remote engine falls back to the device engine`() {
        provider.setEnabled(Feature.REMOTE_TRANSCRIPTION, true)
        health.recordFailure(RemoteSttFailureKind.ModelUnavailable, 404, "No endpoints found for model")

        val transcriber = transcriber()

        assertFalse(transcriber.isRemoteActive())
        assertTrue(transcriber.isRemoteSuspended())
        assertEquals(ON_DEVICE_VERSION, transcriber.version)
    }

    @Test
    fun `a suspended remote engine is not asked for another pass`() = runTest {
        provider.setEnabled(Feature.REMOTE_TRANSCRIPTION, true)
        health.recordFailure(RemoteSttFailureKind.OutOfFunds, 402, "Insufficient credits")
        whenever(parakeet.transcribe(any(), any(), any())) doReturn TranscriptResult.Failed("on device failed too")

        transcriber().transcribe(request())

        verifyBlocking(parakeet) { transcribe(any(), any(), any()) }
        verifyBlocking(remote, never()) { transcribe(any(), any(), any()) }
    }

    @Test
    fun `turning the flag off uses the device engine`() {
        provider.setEnabled(Feature.REMOTE_TRANSCRIPTION, false)

        val transcriber = transcriber()

        assertFalse(transcriber.isRemoteActive())
        assertEquals(ON_DEVICE_VERSION, transcriber.version)
    }

    @Test
    fun `a build without a key stays on the device engine`() {
        provider.setEnabled(Feature.REMOTE_TRANSCRIPTION, true)
        whenever(remote.isAvailable()) doReturn false

        val transcriber = transcriber()

        assertFalse(transcriber.isRemoteActive())
        assertEquals(ON_DEVICE_VERSION, transcriber.version)
    }

    private fun transcriber() = SelectingEpisodeTranscriber(parakeet, remote, health)

    private fun request() = TranscriptRequest(
        episodeUuid = "episode",
        source = TranscriptSource.Downloaded(File("episode.mp3")),
        durationMs = 60_000,
        podcastLanguage = "en",
    )

    private class FakeProvider : ModifiableFeatureProvider {
        private val values = mutableMapOf<Feature, Boolean>()

        override val priority = 0
        override val currentReleaseVersion = ReleaseVersion(Int.MAX_VALUE, Int.MAX_VALUE)

        override fun setEnabled(feature: Feature, enabled: Boolean) {
            values[feature] = enabled
        }

        override fun hasFeature(feature: Feature) = true

        override fun isEnabled(feature: Feature) = values[feature] ?: feature.defaultValue

        override suspend fun awaitInitialization() = true
    }

    private companion object {
        const val REMOTE_VERSION = "remote-nemotron/v1"
        const val ON_DEVICE_VERSION = "parakeet-tdt/v1"
    }
}
