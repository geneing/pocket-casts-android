package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState
import au.com.shiftyjelly.pocketcasts.repositories.ads.jev.JevAdModel
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
