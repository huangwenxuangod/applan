package com.applan.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.applan.network.ChatMessage
import com.applan.network.ApplanClient
import com.applan.network.StreamEvent
import com.applan.network.ToolCall
import com.applan.util.AppConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentReply: String = "",
    val isStreaming: Boolean = false,
    val isConnecting: Boolean = false,
    val showGreeting: Boolean = true
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatVM"
        // UI批量更新间隔：每收到多少个字符更新一次UI，减少recomposition频率
        private const val UI_UPDATE_INTERVAL_CHARS = 3
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _errorEvent = MutableStateFlow<String?>(null)
    val errorEvent: StateFlow<String?> = _errorEvent.asStateFlow()

    // 使用Application context创建ApplanClient，避免内存泄漏
    private val hermesClient = ApplanClient(application.applicationContext).apply {
        // 初始化时从AppConfig读取配置
        updateConfig(AppConfig.getServerUrl(), AppConfig.getApiKey())
    }
    private val messageHistory = mutableListOf<ChatMessage>()

    // 全局协程异常处理器 - 防止任何未捕获异常崩溃App
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Coroutine error", e)
        viewModelScope.launch(Dispatchers.Main.immediate) {
            _errorEvent.value = "连接出错: ${e.message ?: "未知错误"}"
            _uiState.value = _uiState.value.copy(
                isStreaming = false,
                isConnecting = false
            )
        }
    }

    fun updateConfig(serverUrl: String, apiKey: String) {
        hermesClient.updateConfig(serverUrl, apiKey)
        // 同步保存到AppConfig
        AppConfig.saveServerUrl(serverUrl)
        AppConfig.saveApiKey(apiKey)
    }

    fun isConfigured(): Boolean = hermesClient.isConfigured()

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

        messageHistory.add(ChatMessage(role = "user", content = text))
        _uiState.value = _uiState.value.copy(
            messages = messageHistory.toList(),
            currentReply = "",
            isStreaming = true,
            isConnecting = true,
            showGreeting = false
        )

        // 网络请求在IO线程执行
        viewModelScope.launch(exceptionHandler + Dispatchers.IO) {
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
            val fullReply = StringBuilder()
            val toolCalls = mutableMapOf<Int, ToolCall>()
            var charsSinceLastUpdate = 0
            var exitAfterStream = false
            var lockAfterStream = false

            try {
                hermesClient.chatStream(messageHistory.toList()).collect { event ->
                    when (event) {
                        is StreamEvent.Content -> {
                            fullReply.append(event.text)
                            charsSinceLastUpdate += event.text.length
                            // 性能优化：批量更新UI，每N个字符才更新一次，减少recomposition
                            // 第一个字符立即更新显示"正在输入"状态，之后批量更新
                            val shouldUpdate = charsSinceLastUpdate >= UI_UPDATE_INTERVAL_CHARS ||
                                    fullReply.length == event.text.length
                            if (shouldUpdate) {
                                charsSinceLastUpdate = 0
                                // 切换到Main线程更新UI状态
                                withContext(Dispatchers.Main.immediate) {
                                    val state = _uiState.value
                                    _uiState.value = state.copy(
                                        currentReply = fullReply.toString(),
                                        isConnecting = false
                                    )
                                }
                            }
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

                            // 确保最终回复显示完整，在Main线程处理
                            withContext(Dispatchers.Main.immediate) {
                                if (calls.isNotEmpty()) {
                                    messageHistory.add(
                                        ChatMessage(
                                            role = "assistant",
                                            content = replyText,
                                            toolCalls = calls.toMutableList()
                                        )
                                    )

                                    var hasContinue = false

                                    for (tc in calls) {
                                        when (tc.name) {
                                            "lock_screen" -> {
                                                lockAfterStream = true
                                                messageHistory.add(
                                                    ChatMessage(
                                                        role = "tool",
                                                        content = """{"success": true, "message": "屏幕已锁定"}""",
                                                        toolCallId = tc.id
                                                    )
                                                )
                                            }
                                            "exit_app" -> {
                                                exitAfterStream = true
                                                messageHistory.add(
                                                    ChatMessage(
                                                        role = "tool",
                                                        content = """{"success": true, "message": "已放行"}""",
                                                        toolCallId = tc.id
                                                    )
                                                )
                                            }
                                            else -> {
                                                hasContinue = true
                                                messageHistory.add(
                                                    ChatMessage(
                                                        role = "tool",
                                                        content = """{"error": "Unknown tool: ${tc.name}"}""",
                                                        toolCallId = tc.id
                                                    )
                                                )
                                            }
                                        }
                                    }

                                    when {
                                        exitAfterStream -> {
                                            _uiState.value = _uiState.value.copy(
                                                currentReply = replyText,
                                                messages = messageHistory.toList(),
                                                isStreaming = false,
                                                isConnecting = false
                                            )
                                        }
                                        lockAfterStream -> {
                                            _uiState.value = _uiState.value.copy(
                                                currentReply = replyText,
                                                messages = messageHistory.toList(),
                                                isStreaming = false,
                                                isConnecting = false
                                            )
                                        }
                                        hasContinue -> {
                                            shouldLoop = true
                                            _uiState.value = _uiState.value.copy(currentReply = "")
                                        }
                                        else -> {
                                            _uiState.value = _uiState.value.copy(
                                                messages = messageHistory.toList(),
                                                currentReply = "",
                                                isStreaming = false,
                                                isConnecting = false
                                            )
                                        }
                                    }
                                } else {
                                    messageHistory.add(ChatMessage(role = "assistant", content = replyText))
                                    _uiState.value = _uiState.value.copy(
                                        messages = messageHistory.toList(),
                                        currentReply = "",
                                        isStreaming = false,
                                        isConnecting = false
                                    )
                                }
                            }

                            // 在withContext外面处理回调，避免return@collect在withContext内无效
                            // 回调操作Activity，需要切换到Main线程
                            if (exitAfterStream) {
                                withContext(Dispatchers.Main.immediate) {
                                    kotlinx.coroutines.delay(400)
                                    onExitApp()
                                }
                                return@collect
                            }
                            if (lockAfterStream) {
                                withContext(Dispatchers.Main.immediate) {
                                    kotlinx.coroutines.delay(800)
                                    onLockScreen()
                                }
                                return@collect
                            }
                        }
                        is StreamEvent.Error -> {
                            withContext(Dispatchers.Main.immediate) {
                                _errorEvent.value = event.message
                                _uiState.value = _uiState.value.copy(
                                    isStreaming = false,
                                    isConnecting = false
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chat loop error", e)
                withContext(Dispatchers.Main.immediate) {
                    _errorEvent.value = "连接出错: ${e.message ?: "网络异常"}"
                    _uiState.value = _uiState.value.copy(
                        isStreaming = false,
                        isConnecting = false
                    )
                }
            }
        }
    }

    fun dismissError() {
        _errorEvent.value = null
    }
}
