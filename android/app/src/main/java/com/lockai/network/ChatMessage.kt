package com.lockai.network

data class ChatMessage(
    val role: String, // "system" | "user" | "assistant" | "tool"
    val content: String,
    val toolCalls: MutableList<ToolCall>? = null,
    val toolCallId: String? = null
)

data class ToolCall(
    var id: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder()
)

sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    data class ToolCallDelta(val index: Int, val id: String? = null, val name: String? = null, val argumentsDelta: String? = null) : StreamEvent()
    data object StreamEnd : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}
