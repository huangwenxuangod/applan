package com.lockai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lockai.network.ChatMessage
import com.lockai.network.HermesClient
import com.lockai.network.StreamEvent
import com.lockai.network.ToolCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentReply: String = "",
    val isStreaming: Boolean = false,
    val isConnecting: Boolean = false,
    val showGreeting: Boolean = true
)

class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // 错误事件（一次性消费）
    private val _errorEvent = MutableStateFlow<String?>(null)
    val errorEvent: StateFlow<String?> = _errorEvent.asStateFlow()

    private val hermesClient = HermesClient()
    private val messageHistory = mutableListOf<ChatMessage>()

    fun updateConfig(serverUrl: String, apiKey: String) {
        hermesClient.updateConfig(serverUrl, apiKey)
    }

    fun isConfigured(): Boolean = hermesClient.isConfigured()

    /**
     * 重置对话 - 每次解锁/回到前台时调用，开始新一轮审问
     * 服务器端MEMORY.md会保留跨会话记忆（漏洞记录等）
     */
    fun resetConversation() {
        messageHistory.clear()
        _uiState.value = ChatUiState(showGreeting = true)
    }

    fun sendMessage(
        text: String,
        onLockScreen: () -> Unit,
        onExitApp: () -> Unit
    ) {
        if (_uiState.value.isStreaming) return
        if (text.isBlank()) return

        messageHistory.add(ChatMessage("user", text))
        _uiState.value = _uiState.value.copy(
            messages = messageHistory.toList(),
            currentReply = "",
            isStreaming = true,
            isConnecting = true,
            showGreeting = false
        )

        viewModelScope.launch {
            runChatLoop(onLockScreen, onExitApp)
        }
    }

    private suspend fun runChatLoop(
        onLockScreen: () -> Unit,
        onExitApp: () -> Unit
    ) {
        var shouldLoop = true
        while (shouldLoop) {
            shouldLoop = false
            var fullReply = StringBuilder()
            val toolCalls = mutableMapOf<Int, ToolCall>()

            hermesClient.chatStream(messageHistory.toList()).collect { event ->
                when (event) {
                    is StreamEvent.Content -> {
                        fullReply.append(event.text)
                        _uiState.value = _uiState.value.copy(
                            currentReply = fullReply.toString(),
                            isConnecting = false
                        )
                    }
                    is StreamEvent.ToolCallDelta -> {
                        val existing = toolCalls.getOrPut(event.index) { ToolCall() }
                        event.id?.let { existing.id = it }
                        event.name?.let { existing.name = it }
                        event.argumentsDelta?.let { existing.arguments.append(it) }
                    }
                    is StreamEvent.StreamEnd -> {
                        val replyText = fullReply.toString()
                        val calls = toolCalls.values.toList()

                        if (calls.isNotEmpty()) {
                            messageHistory.add(
                                ChatMessage("assistant", replyText, toolCalls = calls.toMutableList())
                            )

                            var hasContinue = false
                            var shouldExit = false
                            var shouldLock = false

                            for (tc in calls) {
                                when (tc.name) {
                                    "lock_screen" -> {
                                        shouldLock = true
                                        messageHistory.add(
                                            ChatMessage(
                                                "tool",
                                                """{"success": true, "message": "屏幕已锁定"}""",
                                                toolCallId = tc.id
                                            )
                                        )
                                    }
                                    "exit_app" -> {
                                        shouldExit = true
                                        messageHistory.add(
                                            ChatMessage(
                                                "tool",
                                                """{"success": true, "message": "已放行"}""",
                                                toolCallId = tc.id
                                            )
                                        )
                                    }
                                    else -> {
                                        hasContinue = true
                                        messageHistory.add(
                                            ChatMessage(
                                                "tool",
                                                """{"error": "Unknown tool: ${tc.name}"}""",
                                                toolCallId = tc.id
                                            )
                                        )
                                    }
                                }
                            }

                            if (shouldExit) {
                                _uiState.value = _uiState.value.copy(
                                    currentReply = replyText,
                                    messages = messageHistory.toList(),
                                    isStreaming = false,
                                    isConnecting = false
                                )
                                kotlinx.coroutines.delay(400)
                                onExitApp()
                                return@collect
                            }

                            if (shouldLock) {
                                _uiState.value = _uiState.value.copy(
                                    currentReply = replyText,
                                    messages = messageHistory.toList(),
                                    isStreaming = false,
                                    isConnecting = false
                                )
                                // 等待800ms让用户看到AI的回复，再执行锁屏
                                kotlinx.coroutines.delay(800)
                                onLockScreen()
                                return@collect
                            }

                            if (hasContinue) {
                                shouldLoop = true
                                _uiState.value = _uiState.value.copy(currentReply = "")
                            } else {
                                _uiState.value = _uiState.value.copy(
                                    messages = messageHistory.toList(),
                                    currentReply = "",
                                    isStreaming = false,
                                    isConnecting = false
                                )
                            }
                        } else {
                            messageHistory.add(ChatMessage("assistant", replyText))
                            _uiState.value = _uiState.value.copy(
                                messages = messageHistory.toList(),
                                currentReply = "",
                                isStreaming = false,
                                isConnecting = false
                            )
                        }
                    }
                    is StreamEvent.Error -> {
                        _errorEvent.value = event.message
                        _uiState.value = _uiState.value.copy(
                            isStreaming = false,
                            isConnecting = false
                        )
                    }
                }
            }
        }
    }

    fun dismissError() {
        _errorEvent.value = null
    }
}
