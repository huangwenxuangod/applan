package com.applan.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.applan.BuildConfig
import com.applan.util.DashboardAudit
import com.applan.util.DashboardAuditSnapshot
import com.applan.util.PolicyEvent
import com.applan.util.PolicyRepository
import com.applan.util.BackupPolicy
import com.applan.util.TimeProfile
import com.applan.util.RankedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
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
import java.util.concurrent.atomic.AtomicBoolean

sealed class DashboardAuditVerification {
    object Aligned : DashboardAuditVerification()
    object Offline : DashboardAuditVerification()
    object Unauthorized : DashboardAuditVerification()
    object Failed : DashboardAuditVerification()
    object Mismatch : DashboardAuditVerification()
}

private sealed class AuditHttpResult<out T> {
    data class Success<T>(val value: T) : AuditHttpResult<T>()
    object Offline : AuditHttpResult<Nothing>()
    object Unauthorized : AuditHttpResult<Nothing>()
    object Failed : AuditHttpResult<Nothing>()
}

class ApplanClient(private var context: Context? = null) {

    companion object {
        private const val TAG = "ApplanClient"
    }

    @Volatile
    private var serverUrl: String = BuildConfig.SERVER_URL
    @Volatile
    private var apiKey: String = BuildConfig.API_KEY

    fun provideContext(ctx: Context) {
        context = ctx.applicationContext
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)  // 30秒心跳检测死连接
            .retryOnConnectionFailure(true)      // 自动重试连接失败
            // 性能优化：配置dispatcher限制并发，不占用过多线程
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 3
                maxRequestsPerHost = 2
            })
            .build()
    }

    fun updateConfig(serverUrl: String, apiKey: String) {
        this.serverUrl = serverUrl.trimEnd('/')
        this.apiKey = apiKey
    }

    fun isConfigured(): Boolean = serverUrl.isNotEmpty()

    fun getServerUrl(): String = serverUrl

    fun syncEvents(events: List<PolicyEvent>): Boolean {
        if (!isConfigured() || apiKey.isBlank() || events.isEmpty()) return false
        return postEvents(events) is AuditHttpResult.Success
    }

    fun syncPolicy(repository: PolicyRepository) {
        if (!isConfigured() || apiKey.isBlank()) return
        val remote = fetchPolicy() ?: return
        val local = repository.backupPolicy()
        if (repository.isPolicyDirty()) {
            val uploaded = putPolicy(remote.version, local) ?: return
            repository.markPolicySynced(uploaded.version)
        } else if (remote.version > local.version) {
            repository.applyRemotePolicy(remote)
        }
    }

    private fun fetchPolicy(): BackupPolicy? = try {
        val request = Request.Builder().url("$serverUrl/v1/policy")
            .header("Authorization", "Bearer $apiKey").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else parsePolicy(response.body?.string().orEmpty())
        }
    } catch (_: IOException) { null }

    private fun putPolicy(baseVersion: Int, policy: BackupPolicy): BackupPolicy? = try {
        val profiles = JSONArray().apply { policy.profiles.forEach { profile -> put(profile.toJson()) } }
        val body = JSONObject().apply {
            put("baseVersion", baseVersion); put("profiles", profiles); put("planModeEnabled", policy.planModeEnabled)
        }
        val request = Request.Builder().url("$serverUrl/v1/policy")
            .put(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else parsePolicy(response.body?.string().orEmpty())
        }
    } catch (_: IOException) { null }

    private fun parsePolicy(body: String): BackupPolicy? = runCatching {
        val value = JSONObject(body)
        val profiles = value.getJSONArray("profiles")
        BackupPolicy(value.getInt("version"), List(profiles.length()) { index ->
            profiles.getJSONObject(index).let { item -> TimeProfile(
                item.getString("id"), item.getJSONArray("weekdays").let { days -> buildSet { for (i in 0 until days.length()) add(days.getInt(i)) } },
                item.getInt("startMinute"), item.getInt("endMinute"),
                item.getJSONArray("allowedPackages").let { apps -> buildSet { for (i in 0 until apps.length()) add(apps.getString(i)) } }
            ) }
        }, value.getBoolean("planModeEnabled"))
    }.getOrNull()

    private fun TimeProfile.toJson() = JSONObject().apply {
        put("id", id); put("weekdays", JSONArray(weekdays.toList())); put("startMinute", startMinute)
        put("endMinute", endMinute); put("allowedPackages", JSONArray(allowedPackages.toList()))
    }

    fun verifyDashboardAudit(
        events: List<PolicyEvent>,
        from: Long,
        to: Long
    ): DashboardAuditVerification {
        if (events.isNotEmpty()) {
            when (postEvents(events)) {
                AuditHttpResult.Offline -> return DashboardAuditVerification.Offline
                AuditHttpResult.Unauthorized -> return DashboardAuditVerification.Unauthorized
                AuditHttpResult.Failed -> return DashboardAuditVerification.Failed
                is AuditHttpResult.Success -> Unit
            }
        }
        return when (val remote = fetchDashboardAudit(from, to)) {
            AuditHttpResult.Offline -> DashboardAuditVerification.Offline
            AuditHttpResult.Unauthorized -> DashboardAuditVerification.Unauthorized
            AuditHttpResult.Failed -> DashboardAuditVerification.Failed
            is AuditHttpResult.Success -> if (DashboardAudit.isAligned(events, from, to, remote.value)) {
                DashboardAuditVerification.Aligned
            } else {
                DashboardAuditVerification.Mismatch
            }
        }
    }

    private fun postEvents(events: List<PolicyEvent>): AuditHttpResult<Unit> {
        val payload = JSONArray().apply {
            events.forEach { event ->
                put(JSONObject().apply {
                    put("id", event.id)
                    put("type", event.type)
                    put("occurredAt", event.occurredAt)
                    put("packageName", event.packageName)
                    put("durationMinutes", event.durationMinutes)
                    put("durationSeconds", event.durationSeconds)
                    put("planId", event.planId)
                })
            }
        }
        return executeAuditRequest(
            Request.Builder()
            .url("$serverUrl/v1/events/batch")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .build()
        ) { Unit }
    }

    private fun fetchDashboardAudit(from: Long, to: Long): AuditHttpResult<DashboardAuditSnapshot> {
        if (!isConfigured() || apiKey.isBlank()) return AuditHttpResult.Unauthorized
        return executeAuditRequest(
            Request.Builder()
                .url("$serverUrl/v1/dashboard/range?from=$from&to=$to")
                .header("Authorization", "Bearer $apiKey")
                .build()
        ) { body -> parseDashboardAuditSnapshot(body) }
    }

    private fun <T : Any> executeAuditRequest(request: Request, parse: (String) -> T?): AuditHttpResult<T> = try {
        client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> parse(response.body?.string().orEmpty())
                    ?.let { AuditHttpResult.Success(it) }
                    ?: AuditHttpResult.Failed
                response.code == 401 || response.code == 403 -> AuditHttpResult.Unauthorized
                else -> AuditHttpResult.Failed
            }
        }
    } catch (e: IOException) {
        Log.d(TAG, "Dashboard audit deferred: ${e.message}")
        AuditHttpResult.Offline
    }

    private fun parseDashboardAuditSnapshot(body: String): DashboardAuditSnapshot? = runCatching {
        val json = JSONObject(body)
        val topApps = json.getJSONArray("topApps")
        DashboardAuditSnapshot(
            blockedCount = json.getInt("blockedCount"),
            temporaryPassCount = json.getInt("temporaryPassCount"),
            planStartedCount = json.getInt("planStartedCount"),
            planEndedEarlyCount = json.getInt("planEndedEarlyCount"),
            planExpiredCount = json.getInt("planExpiredCount"),
            topApps = List(topApps.length()) { index ->
                topApps.getJSONObject(index).let { app ->
                    RankedApp(app.getString("packageName"), app.getInt("count"))
                }
            }
        )
    }.getOrNull()

    /**
     * 检查网络是否可用 - 这个方法在调用时要确保在IO线程
     */
    private fun isNetworkAvailable(): Boolean {
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

    fun chatStream(messages: List<ChatMessage>): Flow<StreamEvent> = callbackFlow {
        // 注意：整个callbackFlow在flowOn(Dispatchers.IO)下执行，所以所有操作都在IO线程

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
        Log.d(TAG, "Connecting to chat endpoint")

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
                    put("description", "放行用户，退出applan回到桌面。只有当用户通过了全部五层消解漏斗检查：1)不是语言陷阱 2)不是错误假设 3)没有逻辑错误 4)事实可验证 5)信息充分，意图指向一个具体的、可验证的、有产出的单步行动时才调用。单步简单任务（如'接电话'）用exit_app。用户说'退出''让我走''我就用一下'等任何主动要求退出的话都绝对不放行。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "grant_plan")
                    put("description", "放行用户执行一个具体的多步计划。当用户说明了要按顺序访问多个App完成特定任务时调用此工具（而非exit_app）。例如用户说'先去微信回客户消息，然后去飞书看项目文档'，调用grant_plan，apps=['微信','飞书']。只有当用户的计划通过了五层消解漏斗、每一步都具体可验证、且涉及2个及以上App时才调用。单步简单任务仍使用exit_app。调用后客户端会持续监听用户实际打开的App，如果打开了不在apps列表中的App，会自动拉回来让你质问。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("apps", JSONObject().apply {
                                put("type", "array")
                                put("items", JSONObject().apply { put("type", "string") })
                                put("description", "用户计划中要用到的App名称列表，使用中文常用名，如['微信','飞书','Chrome']。只包含用户明确提到要使用的App。")
                            })
                            put("plan", JSONObject().apply {
                                put("type", "string")
                                put("description", "用户声称要执行的具体计划的一句话概括，如'先微信回张总合同消息，再飞书查看Q3项目文档'")
                            })
                            put("timeout_minutes", JSONObject().apply {
                                put("type", "integer")
                                put("description", "计划预计需要的时间（分钟），超时自动锁屏。默认15分钟，根据任务复杂度估算，最长60分钟。")
                                put("default", 15)
                            })
                        })
                        put("required", JSONArray().apply { put("apps"); put("plan") })
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
            put("temperature", 0.4)
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
        val streamEnded = AtomicBoolean(false)

        val eventSourceListener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.d(TAG, "SSE connection opened, code=${response.code}")
                if (!response.isSuccessful) {
                    response.close()
                    val errMsg = "服务器返回错误: HTTP ${response.code} - ${response.message}"
                    trySend(StreamEvent.Error(errMsg))
                    close()
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    if (streamEnded.compareAndSet(false, true)) {
                        trySend(StreamEvent.StreamEnd)
                    }
                    close()
                    return
                }
                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) {
                        return
                    }
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
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse SSE event: ${e.message}")
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (streamEnded.compareAndSet(false, true)) {
                    trySend(StreamEvent.StreamEnd)
                }
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                response?.close()
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
    }.flowOn(Dispatchers.IO) // 整个网络请求在IO线程执行

    private fun formatError(t: Throwable?, response: Response?): String {
        if (response != null && !response.isSuccessful) {
            val body = try { response.body?.string()?.take(200) } catch (_: Exception) { null }
            response.close()
            return "服务器错误 (HTTP ${response.code})${body?.let { ": ${it.take(100)}" } ?: ""}"
        }
        return when (t) {
            is UnknownHostException ->
                "无法连接服务器\nDNS解析失败，请检查：\n1. 服务器地址是否正确\n2. 手机是否能访问互联网\n3. 服务器是否在线\n当前地址: $serverUrl"
            is ConnectException ->
                "连接被拒绝\n服务器($serverUrl)拒绝连接，请确认：\n1. applan 服务器已启动\n2. 端口${getPortFromUrl(serverUrl)}已开放\n3. 防火墙/安全组已放行"
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

    private fun getPortFromUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            if (uri.port > 0) uri.port.toString() else if (uri.scheme == "https") "443" else "80"
        } catch (_: Exception) {
            "?"
        }
    }
}
