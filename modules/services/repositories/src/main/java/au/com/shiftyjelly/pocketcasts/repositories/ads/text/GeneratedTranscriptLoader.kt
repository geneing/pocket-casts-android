package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.to.Transcript
import au.com.shiftyjelly.pocketcasts.models.to.TranscriptType
import au.com.shiftyjelly.pocketcasts.repositories.BuildConfig
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.shownotes.generatedTranscriptUrl
import au.com.shiftyjelly.pocketcasts.repositories.transcript.TranscriptParser
import au.com.shiftyjelly.pocketcasts.repositories.transcript.sanitize
import au.com.shiftyjelly.pocketcasts.servers.podcast.TranscriptService
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.source

/**
 * Fetches the generated transcript the show notes advertise for an episode.
 *
 * The transcript stack only records that generated transcript when the show notes of the episode
 * have been processed, which can happen well after playback starts. Ad analysis needs the text as
 * soon as the episode plays, and the URL is deterministic from the episode and podcast, so this
 * loader reads it directly instead of waiting for a screen to have requested it first.
 */
@Singleton
class GeneratedTranscriptLoader @Inject constructor(
    private val episodeManager: EpisodeManager,
    private val transcriptService: TranscriptService,
    private val parsers: Map<TranscriptType, @JvmSuppressWildcards TranscriptParser>,
) {
    suspend fun load(episodeUuid: String): Transcript.Text? {
        val episode = episodeManager.findByUuid(episodeUuid) ?: return null
        val url = urlFor(episode) ?: return null
        val parser = parsers[TranscriptType.Vtt] ?: return null
        return runCatching {
            val entries = withContext(Dispatchers.Default) {
                transcriptService.getTranscriptOrThrow(url).use { body ->
                    parser.parse(body.source()).getOrThrow().sanitize()
                }
            }
            Transcript.Text(
                entries = entries,
                type = TranscriptType.Vtt,
                url = url,
                isGenerated = true,
                episodeUuid = episodeUuid,
                podcastUuid = episode.podcastUuid,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            LogBuffer.e(TAG, error, "Could not fetch the generated transcript for $episodeUuid")
        }.getOrNull()
    }

    /** The URL the show notes advertise for the episode, without fetching anything. */
    fun urlFor(episode: PodcastEpisode): String? {
        if (!episode.hasGeneratedTranscript) return null
        return generatedTranscriptUrl(
            showNotesBaseUrl = BuildConfig.SERVER_SHOW_NOTES_URLS.toHttpUrl(),
            podcastUuid = episode.podcastUuid,
            episodeUuid = episode.uuid,
        )
    }

    private companion object {
        const val TAG = "AdGeneratedTranscript"
    }
}
