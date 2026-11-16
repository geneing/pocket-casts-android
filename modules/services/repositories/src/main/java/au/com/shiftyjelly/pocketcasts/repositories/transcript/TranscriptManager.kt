package au.com.shiftyjelly.pocketcasts.repositories.transcript

import au.com.shiftyjelly.pocketcasts.models.to.Transcript
import kotlinx.coroutines.flow.Flow

interface TranscriptManager {
    fun observeIsTranscriptAvailable(episodeUuid: String): Flow<Boolean>

    fun observeIsGeneratedTranscriptAvailable(episodeUuid: String): Flow<Boolean>

    suspend fun loadTranscript(
        episodeUuid: String,
    ): Transcript?

    suspend fun loadGeneratedTranscript(
        episodeUuid: String,
    ): Transcript.Text?

    suspend fun loadGeneratedChapters(
        episodeUuid: String,
    ): GeneratedChaptersResult

    fun resetInvalidTranscripts(
        episodeUuid: String,
    )

    /**
     * Forgets the transcript cached for an episode, so the next [loadTranscript] reads what is on
     * the device now. The ad analysis uses it after it replaces a published transcript with the one
     * it recognised, because the tab would otherwise keep showing the text it was opened with.
     */
    fun invalidateTranscript(
        episodeUuid: String,
    )
}
