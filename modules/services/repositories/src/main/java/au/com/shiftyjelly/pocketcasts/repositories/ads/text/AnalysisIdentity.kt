package au.com.shiftyjelly.pocketcasts.repositories.ads.text

import au.com.shiftyjelly.pocketcasts.models.to.AdAnalysisState
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

    fun isReusable(
        state: AdAnalysisState?,
        audioSignature: String,
        modelVersion: String,
        sttVersion: String,
    ): Boolean = state?.isComplete == true &&
        state.audioSignature == audioSignature &&
        state.modelVersion == modelVersion &&
        state.sttVersion == sttVersion

    /**
     * True when the stored state came from the transcript pipeline. Audio-only analyses carry
     * empty versions and must not be deleted just because the transcript model changed.
     */
    fun isTranscriptAnalysis(state: AdAnalysisState?): Boolean = state != null && (state.modelVersion.isNotEmpty() || state.sttVersion.isNotEmpty())
}
