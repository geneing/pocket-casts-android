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

    const val SYSTEM = "You answer questions about a single podcast episode. The episode transcript is supplied " +
        "below and is the primary source: when the question is about the episode, answer from it. " +
        "Summarize the episode using only the transcript, without external sources or a sources section. " +
        "For other questions, use web search when useful for facts beyond the transcript, and cite the " +
        "external sources actually used. " +
        "Be concise: reply in a few short sentences, or a short list when one is asked for. " +
        "Never invent names, quotes, numbers or facts, and say plainly when neither the transcript nor " +
        "the search results answer the question. When an answer uses something from the web, keep the " +
        "citation the search results came with."

    private const val TRANSCRIPT_HEADER = "\n\nEpisode transcript:\n"

    /** The system message: the instructions followed by the (already capped) transcript. */
    fun systemContent(transcript: String): String = SYSTEM + TRANSCRIPT_HEADER + transcript
}
