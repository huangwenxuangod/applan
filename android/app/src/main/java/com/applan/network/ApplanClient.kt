package com.applan.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.applan.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class ApplanClient(private val context: Context? = null) {

    companion object {
        private const val TAG = "ApplanClient"
        private const val MAX_RETRY = 2
    }

    private var serverUrl: String = BuildConfig.SERVER_URL
    private var apiKey: String = BuildConfig.API_KEY

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)  // SSE长连接，5分钟
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)  // 30秒心跳检测死连接
            .retryOnConnectionFailure(true)      // 自动重试连接失败
            .build()
    }

    fun updateConfig(serverUrl: String, apiKey: String) {
        this.serverUrl = serverUrl.trimEnd('/')
        this.apiKey = apiKey
    }

    fun isConfigured(): Boolean = serverUrl.isNotEmpty()

    /**
     * 检查网络是否可用
     */
    fun isNetworkAvailable(): Boolean {
        val appCtx = context ?: return true
        return try {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            true
        }
    }

    // 移除自定义getter，避免smart cast问题

    fun chatStream(messages: List<ChatMessage>): Flow<StreamEvent> = callbackFlow {
        // 网络预检查
        if (!isConfigured()) {
            trySend(StreamEvent.Error("服务器地址未配置。请先在设置中配置applan 服务器地址"))
            close()
            return@callbackFlow
        }
        if (!isNetworkAvailable()) {
            trySend(StreamEvent.Error("网络不可用。请检查WiFi/移动数据是否连接正常"))
            close()
            return@callbackFlow
        }

        val url = "$serverUrl/v1/chat/completions"
        Log.d(TAG, "Connecting to: $url")

        val toolsArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "lock_screen")
                    put("description", "立即锁定手机屏幕。当用户意图不纯、找借口、想逃避、想刷手机、意图模糊、说不出具体要做什么时，调用此工具锁屏。也用于用户连续3次以上试图绕过你时直接锁屏。锁屏不回复多余文字，直接锁屏。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "exit_app")
                    put("description", "放行用户，退出applan回到桌面。只有当用户通过了全部五层消解漏斗检查：1)不是语言陷阱 2)不是错误假设 3)没有逻辑错误 4)事实可验证 5)信息充分，意图指向一个具体的、可验证的、有产出的行动时才调用。用户说'退出''让我走''我就用一下'等任何主动要求退出的话都绝对不放行。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                })
            })
        }

        val messagesArray = JSONArray().apply {
            for (msg in messages) {
                put(JSONObject().apply {
                    put("role", msg.role)
                    when {
                        msg.role == "assistant" && msg.toolCalls.isNotEmpty() -> {
                            put("content", msg.content)
                            val tcArr = JSONArray()
                            for (tc in msg.toolCalls) {
                                val funcObj = JSONObject().apply {
                                    put("name", tc.name)
                                    put("arguments", tc.arguments.toString())
                                }
                                tcArr.put(JSONObject().apply {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", funcObj)
                                })
                            }
                            put("tool_calls", tcArr)
                        }
                        msg.role == "tool" -> {
                            put("content", msg.content)
                            put("tool_call_id", msg.toolCallId)
                        }
                        else -> {
                            put("content", msg.content)
                        }
                    }
                })
            }
        }

        val requestBody = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", messagesArray)
            put("stream", true)
            put("tools", toolsArray)
            put("tool_choice", "auto")
        }.toString()

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")

        if (apiKey.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        val request = requestBuilder.build()

        val eventSourceListener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.d(TAG, "SSE connection opened, code=${response.code}")
                if (!response.isSuccessful) {
                    val errMsg = "服务器返回错误: HTTP ${response.code}${response.message?.let { " - $it" } ?: ""}"
                    trySend(StreamEvent.Error(errMsg))
                    close()
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    trySend(StreamEvent.StreamEnd)
                    close()
                    return
                }
                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices") ?: return
                    if (choices.length() == 0) return
                    val choice = choices.getJSONObject(0)
                    val delta = choice.optJSONObject("delta") ?: return

                    val content = if (delta.isNull("content")) null else delta.optString("content")
                    if (!content.isNullOrEmpty()) {
                        trySend(StreamEvent.Content(content))
                    }

                    val toolCalls = delta.optJSONArray("tool_calls")
                    if (toolCalls != null) {
                        for (i in 0 until toolCalls.length()) {
                            val tc = toolCalls.getJSONObject(i)
                            val index = tc.optInt("index", i)
                            val tcId = if (tc.isNull("id")) null else tc.optString("id")
                            val func = tc.optJSONObject("function")
                            val name = if (func == null || func.isNull("name")) null else func.optString("name")
                            val args = if (func == null || func.isNull("arguments")) null else func.optString("arguments")
                            trySend(StreamEvent.ToolCallDelta(index, tcId, name, args))
                        }
                    }

                    val finishReason = if (choice.isNull("finish_reason")) null else choice.optString("finish_reason")
                    if (finishReason == "stop" || finishReason == "tool_calls") {
                        // wait for [DONE]
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse SSE event: ${e.message}, data: ${data.take(200)}")
                }
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(StreamEvent.StreamEnd)
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = formatError(t, response)
                Log.e(TAG, "SSE failure: $errorMsg", t)
                trySend(StreamEvent.Error(errorMsg))
                close()
            }
        }

        val factory = EventSources.createFactory(client)
        val eventSource = factory.newEventSource(request, eventSourceListener)

        awaitClose {
            eventSource.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun formatError(t: Throwable?, response: Response?): String {
        if (response != null && !response.isSuccessful) {
            val body = try { response.body?.string()?.take(200) } catch (_: Exception) { null }
            return "服务器错误 (HTTP ${response.code})${body?.let { ": ${it.take(100)}" } ?: ""}"
        }
        return when (t) {
            is UnknownHostException ->
                "无法连接服务器\nDNS解析失败，请检查：\n1. 服务器地址是否正确\n2. 手机是否在同一WiFi网络\n3. applan 服务器是否正在运行\n当前地址: $serverUrl"
            is ConnectException ->
                "连接被拒绝\n服务器($serverUrl)拒绝连接，请确认：\n1. applan 服务器已启动\n2. 端口8787已开放\n3. 防火墙未阻挡"
            is SocketTimeoutException ->
                "连接超时（服务器响应太慢）\n可能原因：\n1. 服务器负载过高\n2. 网络延迟大\n3. applan 服务器未完全启动\n请稍后重试，或检查服务器状态"
            is IOException -> {
                val msg = t.message ?: ""
                when {
                    "timeout" in msg.lowercase() -> "连接超时: $msg\n请检查网络状况后重试"
                    "refused" in msg.lowercase() -> "连接被拒绝: 服务器端口未开放或服务未启动"
                    "reset" in msg.lowercase() -> "连接被重置: 服务器可能重启了，请稍后重试"
                    "eof" in msg.lowercase() -> "服务器意外断开连接，请重试"
                    else -> "网络错误: ${msg.take(200)}"
                }
            }
            null -> "连接失败: 未知错误，请检查网络"
            else -> "连接错误: ${t.javaClass.simpleName}: ${t.message?.take(200) ?: "未知"}"
        }
    }
}
