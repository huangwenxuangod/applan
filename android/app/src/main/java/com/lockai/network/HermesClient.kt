package com.lockai.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
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
import java.util.concurrent.TimeUnit

class HermesClient(
    private var serverUrl: String = "",
    private var apiKey: String = ""
) {
    companion object {
        private const val TAG = "HermesClient"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 长连接无超时
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun updateConfig(serverUrl: String, apiKey: String) {
        this.serverUrl = serverUrl.trimEnd('/')
        this.apiKey = apiKey
    }

    fun isConfigured(): Boolean = serverUrl.isNotEmpty()

    /**
     * 工具定义 - 每次请求都带上
     */
    private fun buildTools(): JSONArray {
        return JSONArray().apply {
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "lock_screen")
                    put("description", "锁定设备屏幕。当判定用户在找借口想玩手机、需要阻止用户继续使用手机时调用。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                        put("required", JSONArray())
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "exit_app")
                    put("description", "放行用户，退出LockAI回到桌面。极其严格：仅当用户的意图通过了五层消解漏斗检查（无模糊词、假设成立、逻辑正确、事实可验证、有具体可执行的第一步），指向明确的有价值行动（如写代码、回复客户、紧急事务）时才调用。用户说'退出''让我走''不用你管'本身是bullshit信号，绝对不能因此调用此工具。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                        put("required", JSONArray())
                    })
                })
            })
        }
    }

    /**
     * 流式聊天 - 返回Flow<StreamEvent>
     */
    fun chatStream(messages: List<ChatMessage>): Flow<StreamEvent> = callbackFlow {
        if (!isConfigured()) {
            trySend(StreamEvent.Error("未配置服务器地址或API密钥"))
            close()
            return@callbackFlow
        }

        // 构建请求体
        val requestBody = JSONObject().apply {
            put("model", "hermes-agent")
            put("stream", true)
            put("messages", JSONArray().apply {
                messages.forEach { msg ->
                    val obj = JSONObject().put("role", msg.role).put("content", msg.content)
                    // tool_calls
                    msg.toolCalls?.let { calls ->
                        if (calls.isNotEmpty()) {
                            obj.put("tool_calls", JSONArray().apply {
                                calls.forEach { tc ->
                                    put(JSONObject().apply {
                                        put("id", tc.id)
                                        put("type", "function")
                                        put("function", JSONObject().apply {
                                            put("name", tc.name)
                                            put("arguments", tc.arguments.toString())
                                        })
                                    })
                                }
                            })
                        }
                    }
                    // tool_call_id
                    msg.toolCallId?.let { obj.put("tool_call_id", it) }
                    put(obj)
                }
            })
            put("tools", buildTools())
            put("tool_choice", "auto")
        }.toString()

        Log.d(TAG, "Request: $requestBody")

        val requestBuilder = Request.Builder()
            .url("$serverUrl/v1/chat/completions")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
        if (apiKey.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }
        val request = requestBuilder.build()

        val eventSourceListener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.d(TAG, "SSE connection opened")
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
                    val finishReason = choice.optString("finish_reason", "")

                    // 文本增量
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        trySend(StreamEvent.Content(content))
                    }

                    // tool_calls增量
                    val tcArray = delta.optJSONArray("tool_calls")
                    if (tcArray != null) {
                        for (i in 0 until tcArray.length()) {
                            val tc = tcArray.getJSONObject(i)
                            val index = tc.optInt("index", 0)
                            var tcId: String? = null
                            var tcName: String? = null
                            var tcArgs: String? = null

                            val idStr = tc.optString("id", "")
                            if (idStr.isNotEmpty()) tcId = idStr

                            tc.optJSONObject("function")?.let { fn ->
                                val name = fn.optString("name", "")
                                if (name.isNotEmpty()) tcName = name
                                val args = fn.optString("arguments", "")
                                if (args.isNotEmpty()) tcArgs = args
                            }
                            trySend(StreamEvent.ToolCallDelta(index, tcId, tcName, tcArgs))
                        }
                    }

                    // 结束
                    if (finishReason == "stop" || finishReason == "tool_calls") {
                        trySend(StreamEvent.StreamEnd)
                        close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error: ${e.message}, data: $data", e)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(StreamEvent.StreamEnd)
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = t?.message
                    ?: response?.let { "HTTP ${it.code}: ${it.message}" }
                    ?: "连接失败"
                Log.e(TAG, "SSE failure: $errorMsg", t)
                trySend(StreamEvent.Error(errorMsg))
                close(t ?: Exception(errorMsg))
            }
        }

        val factory = EventSources.createFactory(client)
        val eventSource = factory.newEventSource(request, eventSourceListener)

        awaitClose {
            eventSource.cancel()
        }
    }

    /**
     * 测试连接 - 调用 /v1/models 端点验证连通性和API Key
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverUrl/v1/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "Test connection failed", e)
            false
        }
    }
}
