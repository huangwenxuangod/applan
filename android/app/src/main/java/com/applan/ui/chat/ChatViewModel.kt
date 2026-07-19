package com.applan.ui.chat

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.applan.network.ApplanClient
import com.applan.network.ChatMessage
import com.applan.network.GrantPlanResult
import com.applan.network.StreamEvent
import com.applan.network.ToolCall
import com.applan.util.AppConfig
import com.applan.util.AppPackageResolver
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
        private const val UI_UPDATE_INTERVAL_CHARS = 3
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _errorEvent = MutableStateFlow<String?>(null)
    val errorEvent: StateFlow<String?> = _errorEvent.asStateFlow()

    private val hermesClient = ApplanClient(application.applicationContext).apply {
        updateConfig(AppConfig.getServerUrl(), AppConfig.getApiKey())
    }
    private val messageHistory = mutableListOf<ChatMessage>()
    private var streamJob: Job? = null

    @Volatile
    private var pendingSystemMessage: String? = null
    @Volatile
    private var pendingLock: (() -> Unit)? = null
    @Volatile
    private var pendingExit: (() -> Unit)? = null
    @Volatile
    private var pendingGrant: ((GrantPlanResult) -> Unit)? = null

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
        AppConfig.saveServerUrl(serverUrl)
        AppConfig.saveApiKey(apiKey)
    }

    fun provideContext(context: Context) {
        hermesClient.provideContext(context)
    }

    fun isConfigured(): Boolean = hermesClient.isConfigured()

    fun resetConversation() {
        streamJob?.cancel()
        streamJob = null
        messageHistory.clear()
        pendingSystemMessage = null
        pendingLock = null
        pendingExit = null
        pendingGrant = null
        _uiState.value = ChatUiState(showGreeting = true)
        _errorEvent.value = null
    }

    fun sendMessage(
        text: String,
        onLockScreen: () -> Unit,
        onExitApp: () -> Unit,
        onGrantPlan: ((GrantPlanResult) -> Unit)? = null
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

        viewModelScope.launch(exceptionHandler + Dispatchers.IO) {
            runChatLoop(onLockScreen, onExitApp, onGrantPlan)
        }
    }

    fun triggerViolationChallenge(
        systemMessage: String,
        onLockScreen: () -> Unit,
        onExitApp: () -> Unit,
        onGrantPlan: ((GrantPlanResult) -> Unit)? = null
    ) {
        if (_uiState.value.isStreaming) {
            Log.d(TAG, "Already streaming, ignoring violation trigger")
            return
        }
        pendingSystemMessage = systemMessage
        pendingLock = onLockScreen
        pendingExit = onExitApp
        pendingGrant = onGrantPlan
        Log.d(TAG, "Queued violation challenge: ${systemMessage.take(50)}")

        if (_errorEvent.value != null) {
            checkAndRunPending()
        } else {
            _uiState.value = _uiState.value.copy(
                currentReply = "",
                isStreaming = true,
                isConnecting = true,
                showGreeting = false
            )
            streamJob = viewModelScope.launch(exceptionHandler + Dispatchers.IO) {
                runChatLoop(onLockScreen, onExitApp, onGrantPlan)
            }
        }
    }

    private suspend fun runChatLoop(
        onLockScreen: () -> Unit,
        onExitApp: () -> Unit,
        onGrantPlan: ((GrantPlanResult) -> Unit)? = null
    ) {
        val pending = pendingSystemMessage
        if (pending != null) {
            messageHistory.add(ChatMessage(role = "system", content = pending))
            pendingSystemMessage = null
        }

        // Plan Mode开启时注入系统提示：优先使用grant_plan而非exit_app，违规直接锁定不对话
        if (AppConfig.isPlanModeEnabled()) {
            val planModeHint = "[Plan Mode已开启] 当前处于计划守护模式。" +
                "规则：1) 优先使用grant_plan工具而非exit_app，即使用户说'退出'或'不玩了'也要根据其意图创建计划放行；" +
                "2) exit_app仅在用户明确表示工作完成且不再使用手机时使用；" +
                "3) 用户违规打开不在计划内的App会被直接重新锁定，不会回来和你对话。"
            // 避免重复注入（检查最后几条消息）
            val alreadyInjected = messageHistory.takeLast(5).any {
                it.role == "system" && it.content.startsWith("[Plan Mode已开启]")
            }
            if (!alreadyInjected) {
                messageHistory.add(ChatMessage(role = "system", content = planModeHint))
            }
        }

        var shouldLoop = true
        while (shouldLoop) {
            shouldLoop = false
            val fullReply = StringBuilder()
            val toolCalls = mutableMapOf<Int, ToolCall>()
            var charsSinceLastUpdate = 0
            var exitAfterStream = false
            var lockAfterStream = false
            var grantAfterStream: GrantPlanResult? = null

            try {
                hermesClient.chatStream(messageHistory.toList()).collect { event ->
                    when (event) {
                        is StreamEvent.Content -> {
                            fullReply.append(event.text)
                            charsSinceLastUpdate += event.text.length
                            val shouldUpdate = charsSinceLastUpdate >= UI_UPDATE_INTERVAL_CHARS ||
                                    fullReply.length == event.text.length
                            if (shouldUpdate) {
                                charsSinceLastUpdate = 0
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
                                            "grant_plan" -> {
                                                val result = parseGrantPlanArgs(tc.arguments.toString(), getApplication())
                                                if (result != null) {
                                                    grantAfterStream = result
                                                    messageHistory.add(
                                                        ChatMessage(
                                                            role = "tool",
                                                            content = """{"success": true, "plan": "${result.planDescription}", "apps": ${result.appNames}, "timeout": ${result.timeoutMinutes}}""",
                                                            toolCallId = tc.id
                                                        )
                                                    )
                                                } else {
                                                    hasContinue = true
                                                    messageHistory.add(
                                                        ChatMessage(
                                                            role = "tool",
                                                            content = """{"error": "无法解析计划参数，请提供具体的app名称列表"}""",
                                                            toolCallId = tc.id
                                                        )
                                                    )
                                                }
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
                                        grantAfterStream != null -> {
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
                            if (grantAfterStream != null) {
                                val result = grantAfterStream!!
                                withContext(Dispatchers.Main.immediate) {
                                    kotlinx.coroutines.delay(400)
                                    onGrantPlan?.invoke(result)
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
                            if (pendingSystemMessage != null) {
                                checkAndRunPending()
                                return@collect
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

    private fun parseGrantPlanArgs(argsJson: String, context: Context): GrantPlanResult? {
        return try {
            val json = JSONObject(argsJson)
            val appsArray = json.optJSONArray("apps") ?: return null
            val plan = json.optString("plan", "")
            val timeoutMinutes = json.optInt("timeout_minutes", 15)

            val appNames = mutableListOf<String>()
            for (i in 0 until appsArray.length()) {
                appNames.add(appsArray.getString(i))
            }

            val (packages, unresolved) = AppPackageResolver.resolveAppNames(context, appNames)
            GrantPlanResult(
                planDescription = plan,
                appNames = appNames,
                resolvedPackages = packages,
                unresolvedApps = unresolved,
                timeoutMinutes = timeoutMinutes.coerceIn(1, 60)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse grant_plan args: $argsJson", e)
            null
        }
    }

    private fun checkAndRunPending() {
        val pending = pendingSystemMessage
        val lock = pendingLock
        val exit = pendingExit
        if (pending != null && lock != null && exit != null) {
            val grant = pendingGrant
            pendingSystemMessage = null
            pendingLock = null
            pendingExit = null
            pendingGrant = null
            Log.d(TAG, "Running pending violation system message: ${pending.take(50)}")
            _uiState.value = _uiState.value.copy(
                currentReply = "",
                isStreaming = true,
                isConnecting = true,
                showGreeting = false
            )
            streamJob = viewModelScope.launch(exceptionHandler + Dispatchers.IO) {
                runChatLoop(lock, exit, grant)
            }
        } else {
            pendingSystemMessage = null
            pendingLock = null
            pendingExit = null
            pendingGrant = null
        }
    }

    fun dismissError() {
        _errorEvent.value = null
    }
}
