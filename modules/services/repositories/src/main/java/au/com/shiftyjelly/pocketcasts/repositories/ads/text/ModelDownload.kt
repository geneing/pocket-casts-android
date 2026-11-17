package au.com.shiftyjelly.pocketcasts.repositories.ads.text

/**
 * The outcome of making sure a speech engine's model is on the device.
 *
 * It lives beside [ModelDownloadProgress] rather than inside an engine's package because it is the
 * shape a caller sees: the worker that reports progress and the scheduler that decides whether a
 * pass can start should not have to know which recogniser is being installed to ask the question.
 */
sealed interface ModelDownload {
    /** Every file was already there, which is the normal case for a private build. */
    data object Present : ModelDownload

    data class Downloaded(val bytes: Long, val wallMs: Long) : ModelDownload

    /** The model is missing and the only way to get it right now would be over metered data. */
    data object NeedsUnmeteredNetwork : ModelDownload
}
