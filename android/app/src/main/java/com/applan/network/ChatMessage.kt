package com.applan.network

import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: MutableList<ToolCall> = mutableListOf()
)

data class ToolCall(
    var id: String = "",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder()
)

sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsDelta: String? = null
    ) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
    data object StreamEnd : StreamEvent()
}
