package com.kaiwa.chat

/**
 * One line of the conversation.
 *
 * [role] and [content] are what the API is sent, and [reasoning] goes back with them where a
 * provider needs it. [pending] and [isError] exist so the list can render transient rows, and
 * [time] and the token counts are kept so the transcript can say when a message arrived and
 * what it cost - neither of those goes near a request.
 */
data class Message(
    val role: String,
    val content: String,
    val pending: Boolean = false,
    val isError: Boolean = false,
    /** When it was written. 0 for anything saved before the transcript carried times. */
    val time: Long = 0L,
    /** What the turn cost, summed over its tool rounds. 0 when the server said nothing. */
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    /**
     * The model's own reasoning behind a reply, as its `reasoning_content`, or "" when it sent
     * none. Never shown: it is kept because DeepSeek's thinking mode refuses a request that
     * carries tools unless every earlier reply comes back with its reasoning attached.
     */
    val reasoning: String = ""
) {
    val isUser: Boolean get() = role == ROLE_USER

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"
    }
}
