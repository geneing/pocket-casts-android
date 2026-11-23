package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevAdModel
import au.com.shiftyjelly.pocketcasts.repositories.file.FileStorage
import java.io.File

/**
 * Identities that pin an analysis to the audio file and inference stack that produced it.
 *
 * The audio signature is size + last-modified time: cheap, stable for a finished download, and
 * different after a re-download that may carry a new ad stitch. Model and STT versions make a
 * bundle or recognizer upgrade invalidate old segments instead of serving them.
 */
internal object AnalysisIdentity {
    fun audioSignature(file: File): String = "${file.length()}:${file.lastModified()}"

    /** A stream has no file to measure; the URL identifies it well enough for reuse and staleness. */
    fun audioSignature(source: TranscriptSource): String = when (source) {
        is TranscriptSource.Downloaded -> audioSignature(source.file)
        is TranscriptSource.Stream -> "stream:${source.url.hashCode()}"
    }

    /**
     * The downloaded file holding [episode]'s audio, by its recorded path or by the path downloads
     * write to.
     *
     * Recognition and the queue both have to answer "which audio is this episode's?" the same way:
     * a download worker asks for analysis before the episode row records its path, so the recorded
     * path alone misses a file that is already on disk. Two answers that disagree would invalidate
     * a transcript on every check and recognise the episode forever.
     */
    fun audioFile(episode: BaseEpisode, fileStorage: FileStorage): File? = episode.downloadedFilePath?.let(::File)?.takeIf { it.isFile }
        ?: fileStorage.getOrCreatePodcastEpisodeFile(episode)?.takeIf { it.isFile && it.length() > 0L }

    /**
     * True when a transcript recognised from [recordedSignature] can be shown against [audioFile].
     *
     * The signature is the audio the pass actually read, not the audio the episode has now. A marker
     * written before signatures were recorded cannot say which copy it heard; all it leaves is
     * timing, so it is distrusted only when both signs of a different copy are there: the file was
     * written after the pass was queued (so the pass may have streamed the episode instead), and the
     * transcript stops short of the episode (so the audio it heard was shorter than this one — the
     * shape of a copy without the inserted ads).
     */
    fun transcriptBelongsToAudio(
        recordedSignature: String?,
        audioFile: File,
        queuedAtMs: Long,
        transcriptEndMs: Long,
        episodeDurationMs: Int,
    ): Boolean {
        if (recordedSignature != null) return recordedSignature == audioSignature(audioFile)
        val fileFollowedThePass = audioFile.lastModified() > queuedAtMs
        val endsShort = episodeDurationMs > 0 && transcriptEndMs < episodeDurationMs - TRANSCRIPT_TAIL_TOLERANCE_MS
        return !(fileFollowedThePass && endsShort)
    }

    /**
     * How far short of the episode a finished transcript may end, in milliseconds.
     *
     * Recognition stops on the audio's own end, so a whole transcript ends within a window of the
     * episode unless the audio it read was shorter. The tail is allowed for a last window of music
     * and for a duration the feed rounds up.
     */
    const val TRANSCRIPT_TAIL_TOLERANCE_MS = 30_000L

    /**
     * True when a complete transcript analysis exists for the same audio file, recognizer, model
     * and prompt revision, so the transcript is not classified twice. State written by the audio
     * analyzer carries no transcript identity and is never reused.
     */
    fun isReusableTranscript(
        state: AdAnalysisState?,
        audioSignature: String,
        sttVersion: String,
        model: JevAdModel,
        promptVersion: Int,
    ): Boolean = state?.isComplete == true &&
        state.audioSignature == audioSignature &&
        state.sttVersion == sttVersion &&
        isTranscriptModelVersion(state.modelVersion, model, promptVersion)

    /**
     * True when the stored state came from the transcript pipeline. Audio-only analyses carry
     * empty versions and must not be deleted just because the transcript model changed.
     */
    fun isTranscriptAnalysis(state: AdAnalysisState?): Boolean = state != null && (state.modelVersion.isNotEmpty() || state.sttVersion.isNotEmpty())

    /**
     * The analysis identity of a jev run: the model that actually answered (responses carry a
     * dated suffix such as `respan/span-02-20261015`) and the prompt revision. Falling back to the
     * requested slug keeps a run that received no `model` field attributable.
     */
    fun transcriptModelVersion(responseModel: String?, model: JevAdModel, promptVersion: Int): String {
        val name = responseModel?.takeIf { it.isNotBlank() } ?: model.slug
        return "$name#prompt$promptVersion"
    }

    /**
     * True when [version] was produced by [model] at [promptVersion]. A response model is matched
     * by prefix because its dated suffix changes without the model changing.
     */
    fun isTranscriptModelVersion(version: String, model: JevAdModel, promptVersion: Int): Boolean = version.startsWith(model.slug) && version.endsWith("#prompt$promptVersion")
}
