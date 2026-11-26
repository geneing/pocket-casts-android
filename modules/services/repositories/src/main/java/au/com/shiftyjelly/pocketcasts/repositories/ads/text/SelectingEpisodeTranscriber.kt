package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.ParakeetEpisodeTranscriber
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote.RemoteAsrHealth
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote.RemoteEpisodeTranscriber
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The recogniser the app actually uses: remote when [Feature.REMOTE_TRANSCRIPTION] is on, the
 * build has an OpenRouter key and remote transcription is not suspended, Parakeet otherwise.
 *
 * The choice is made per call rather than at injection so a dev toggle takes effect on the next
 * pass without restarting. Because [version] follows the active engine, flipping the toggle
 * invalidates every transcript recognised by the other one and the queue recognises those episodes
 * again; the ad analysis built on the old cues is reset with them.
 *
 * [RemoteAsrHealth] can also take the remote engine away: once its failures are consistent it
 * suspends remote transcription and this falls back to Parakeet, with no one-shot attempt per pass.
 * The engine that ran is what [version] reports, so a transcript recognised during a fallback is a
 * Parakeet transcript and is not rewritten by a later remote pass it would not match.
 */
@Singleton
class SelectingEpisodeTranscriber @Inject constructor(
    private val parakeet: ParakeetEpisodeTranscriber,
    private val remote: RemoteEpisodeTranscriber,
    private val health: RemoteAsrHealth,
) : EpisodeTranscriber {
    override val version: String get() = active().version

    override fun isAvailable(): Boolean = active().isAvailable()

    override fun availability(podcastLanguage: String?): TranscriptAvailability = active().availability(podcastLanguage)

    /** True when a pass would upload the audio instead of recognising it on the device. */
    fun isRemoteActive(): Boolean = FeatureFlag.isEnabled(Feature.REMOTE_TRANSCRIPTION) && !health.isSuspended() && remote.isAvailable()

    /** True once the remote engine has been suspended for consistent failures. */
    fun isRemoteSuspended(): Boolean = health.isSuspended()

    /**
     * Recognises on the device whatever the flag says. Used by the fallback, which has already
     * thrown the remote pass's cues away and must not ask for the remote engine again.
     */
    suspend fun transcribeOnDevice(
        request: TranscriptRequest,
        onModelProgress: (ModelDownloadProgress) -> Unit = {},
        onPartialCues: suspend (List<TranscriptCue>) -> Unit = {},
    ): TranscriptResult = parakeet.transcribe(request, onModelProgress, onPartialCues)

    override suspend fun transcribe(
        request: TranscriptRequest,
        onModelProgress: (ModelDownloadProgress) -> Unit,
        onPartialCues: suspend (List<TranscriptCue>) -> Unit,
    ): TranscriptResult = active().transcribe(request, onModelProgress, onPartialCues)

    private fun active(): EpisodeTranscriber {
        if (!FeatureFlag.isEnabled(Feature.REMOTE_TRANSCRIPTION)) return parakeet
        if (health.isSuspended()) {
            if (!reportedSuspension) {
                reportedSuspension = true
                LogBuffer.i(TAG, "Remote transcription is suspended after repeated failures; recognising on device")
            }
            return parakeet
        }
        if (!remote.isAvailable()) {
            if (!reportedMissingKey) {
                reportedMissingKey = true
                LogBuffer.w(TAG, "Remote transcription is on but no OpenRouter key is configured; recognising on device")
            }
            return parakeet
        }
        return remote
    }

    @Volatile
    private var reportedMissingKey = false

    @Volatile
    private var reportedSuspension = false

    private companion object {
        const val TAG = "RemoteAsr"
    }
}
