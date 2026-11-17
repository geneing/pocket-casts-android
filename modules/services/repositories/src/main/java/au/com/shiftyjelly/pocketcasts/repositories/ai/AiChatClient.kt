package au.com.shiftyjelly.pocketcasts.repositories.ai

/**
 * The AI tab's chat client: one question about one episode transcript, answered from that
 * transcript alone.
 *
 * Implementations never throw to their caller. A missing API key is [AiChatResult.NoApiKey] and
 * every transport, HTTP or JSON problem is [AiChatResult.Failed].
 */
interface AiChatClient {
    /**
     * Asks [question] about [transcript], with [history] as the conversation so far. [history] holds
     * the turns before [question] whose answers the user still sees, so the model can follow up.
     */
    suspend fun ask(
        question: String,
        transcript: String,
        history: List<AiChatMessage> = emptyList(),
    ): AiChatResult
}

/** One turn of the conversation, as the tab keeps it. */
data class AiChatMessage(
    val role: Role,
    val content: String,
) {
    enum class Role(val wireValue: String) {
        User("user"),
        Assistant("assistant"),
    }
}

/** Outcome of one question. */
sealed interface AiChatResult {
    data class Answer(
        val text: String,
        val costUsd: Double?,
    ) : AiChatResult

    /** No API key is configured, so no request was made. */
    data object NoApiKey : AiChatResult

    /** The request was made or attempted and produced nothing usable. */
    data class Failed(val reason: String) : AiChatResult
}
