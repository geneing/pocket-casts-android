package au.com.shiftyjelly.pocketcasts.repositories.ai

/**
 * Everything that defines the AI tab's request to OpenRouter, in one place.
 *
 * The transcript is never trusted to fit a prompt by accident: the caller caps it before it gets
 * here, and the model itself has a 1M-token context, so a whole episode is comfortably inside it.
 */
object AiChatPrompt {
    /** DeepSeek V4.1 Flash on OpenRouter: cheap, fast, and wide enough for one episode. */
    const val MODEL = "deepseek/deepseek-v4.1-flash-1017"

    const val MAX_TOKENS = 1024

    const val SYSTEM = "You answer questions about a single podcast episode, using only the episode transcript " +
        "supplied below. Be concise: reply in a few short sentences, or a short list when one is asked for. " +
        "Never use outside knowledge and never invent names, quotes, numbers or facts. " +
        "If the transcript does not cover the question, say that you do not know."

    private const val TRANSCRIPT_HEADER = "\n\nEpisode transcript:\n"

    /** The system message: the instructions followed by the (already capped) transcript. */
    fun systemContent(transcript: String): String = SYSTEM + TRANSCRIPT_HEADER + transcript
}
