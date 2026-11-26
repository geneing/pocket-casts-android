package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.repositories.ads.text.parakeet.ParakeetEpisodeTranscriber
import au.com.shiftyjelly.pocketcasts.repositories.ads.text.remote.RemoteEpisodeTranscriber
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The recogniser the app actually uses: remote when [Feature.REMOTE_TRANSCRIPTION] is on and the
 * build has an OpenRouter key, Parakeet otherwise.
 *
 * The choice is made per call rather than at injection so a dev toggle takes effect on the next
 * pass without restarting. Because [version] follows the active engine, flipping the toggle
 * invalidates every transcript recognised by the other one and the queue recognises those episodes
 * again; the ad analysis built on the old cues is reset with them.
 *
 * A build without an OpenRouter key stays on the local engine even with the flag on: there is
 * nothing to call, and falling back is friendlier than leaving the episode untranscribed. The first
 * time that happens it is logged once, because a flag that silently does nothing is a bug report.
 */
@Singleton
class SelectingEpisodeTranscriber @Inject constructor(
    private val parakeet: ParakeetEpisodeTranscriber,
    private val remote: RemoteEpisodeTranscriber,
) : EpisodeTranscriber {
    override val version: String get() = active().version

    override fun isAvailable(): Boolean = active().isAvailable()

    override fun availability(podcastLanguage: String?): TranscriptAvailability = active().availability(podcastLanguage)

    override suspend fun transcribe(
        request: TranscriptRequest,
        onModelProgress: (ModelDownloadProgress) -> Unit,
        onPartialCues: suspend (List<TranscriptCue>) -> Unit,
    ): TranscriptResult = active().transcribe(request, onModelProgress, onPartialCues)

    private fun active(): EpisodeTranscriber {
        if (!FeatureFlag.isEnabled(Feature.REMOTE_TRANSCRIPTION)) return parakeet
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

    private companion object {
        const val TAG = "RemoteAsr"
    }
}
