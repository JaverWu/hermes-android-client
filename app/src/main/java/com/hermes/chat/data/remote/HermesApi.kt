package com.hermes.chat.data.remote

import android.util.Log
import com.hermes.chat.data.model.ApprovalRequest
import com.hermes.chat.data.model.ChatMessage
import com.hermes.chat.data.model.ToolProgressEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Hermes 后端客户端。
 *
 * 对接 Hermes Agent 的 API Server（默认 8642 端口），支持两种对话模式：
 *  1) 普通流式：POST /v1/chat/completions（stream:true），SSE 事件包括
 *     - 文本增量：`data: {"choices":[{"delta":{"content":"..."}}]}`（以 `data: [DONE]` 结束）
 *     - 思考流：delta 中的 `reasoning_content` / `reasoning` / `thinking` 字段
 *     - 工具进度：`event: hermes.tool.progress` + data `{id,emoji,title,status,preview}`
 *     - 推理可用：`event: reasoning.available`
 *     - 审批请求：`event: approval_request`
 *  2) 长运行模式：POST /v1/runs 创建任务，再用 GET /v1/runs/{id}/events 订阅结构化事件流，
 *     适合后台任务。事件格式与普通流式一致，故两套路径共用 [streamLoop] 分发。
 *
 * 认证：请求头统一带 `Authorization: Bearer <API_SERVER_KEY>`。
 */
class HermesApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // 空闲 2 分钟视为死连接，触发 SocketTimeoutException → 重试
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS) // 整轮调用 5 分钟封顶，防无限挂起
        .pingInterval(30, TimeUnit.SECONDS) // HTTP/2 保活 + 探测死连接
        .retryOnConnectionFailure(true)
        .build()

    // ===================== 普通流式对话 =====================

    suspend fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String = "hermes-agent",
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit = {},
        onStatus: (eventType: String, data: String) -> Unit = { _, _ -> },
        onToolProgress: (ToolProgressEvent) -> Unit = {},
        onApproval: (ApprovalRequest) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val url = normalizeChatCompletionsUrl(baseUrl)
            val request = buildRequest(url, apiKey, buildBody(model, messages))
            streamLoop(request, onDelta, onThinking, onStatus, onToolProgress, onApproval, onDone, onError)
        } catch (e: Exception) {
            onError(e)
        }
    }

    // ===================== 长运行模式（/v1/runs） =====================

    /** 创建一次 run，通过 [onRunId] 返回 run id。 */
    suspend fun createRun(
        baseUrl: String,
        apiKey: String,
        model: String = "hermes-agent",
        messages: List<ChatMessage>,
        onRunId: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val url = normalizeRunsUrl(baseUrl)
            val request = buildRequest(url, apiKey, buildBody(model, messages))
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                onError(java.io.IOException("HTTP ${response.code}: ${response.body?.string().orEmpty()}"))
                return@withContext
            }
            val json = JSONObject(response.body?.string().orEmpty())
            val id = json.optString("id", "")
            if (id.isBlank()) onError(java.io.IOException("Hermes 未返回 run id"))
            else onRunId(id)
        } catch (e: Exception) {
            onError(e)
        }
    }

    /** 订阅某个 run 的事件流（GET /v1/runs/{id}/events），事件分发与普通流式一致。 */
    suspend fun subscribeRunEvents(
        baseUrl: String,
        apiKey: String,
        runId: String,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit = {},
        onStatus: (eventType: String, data: String) -> Unit = { _, _ -> },
        onToolProgress: (ToolProgressEvent) -> Unit = {},
        onApproval: (ApprovalRequest) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val url = normalizeRunEventsUrl(baseUrl, runId)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "text/event-stream")
                .get()
                .build()
            streamLoop(request, onDelta, onThinking, onStatus, onToolProgress, onApproval, onDone, onError)
        } catch (e: Exception) {
            onError(e)
        }
    }

    // ===================== SSE 核心循环 =====================

    private suspend fun streamLoop(
        request: Request,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit,
        onStatus: (eventType: String, data: String) -> Unit,
        onToolProgress: (ToolProgressEvent) -> Unit,
        onApproval: (ApprovalRequest) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        try {
            Log.d("HermesApi", "streamLoop starting")
            consumeSse(request) { eventType, data ->
                when {
                    eventType == "approval_request" ->
                        parseApproval(data)?.let { onApproval(it) }
                    eventType == "hermes.tool.progress" ->
                        parseToolProgress(data)?.let { onToolProgress(it) }
                    eventType == "message.delta" -> {
                        // 部分网关以命名事件推送文本增量（OpenAI 兼容 JSON 或裸文本）
                        val content = parseContentDelta(data)
                        if (content != null) onDelta(content)
                        else if (data.isNotBlank()) onDelta(data)
                        parseThinkingDelta(data)?.let { onThinking(it) }
                    }
                    eventType == "reasoning.available" ->
                        onStatus(eventType, data)
                    eventType != null ->
                        onStatus(eventType, data)
                    else -> {
                        parseContentDelta(data)?.let { onDelta(it) }
                        parseThinkingDelta(data)?.let { onThinking(it) }
                    }
                }
            }
            Log.d("HermesApi", "streamLoop completed normally → onDone")
            onDone()
        } catch (e: Exception) {
            Log.d("HermesApi", "streamLoop error: ${e.javaClass.simpleName}: ${e.message}")
            onError(e)
        }
    }

    /** 读取 SSE：`event:` 设置事件类型，`data:` 回调 (eventType, data)。`data: [DONE]` 结束循环。 */
    private fun consumeSse(request: Request, onEvent: (eventType: String?, data: String) -> Unit) {
        Log.d("HermesApi", "SSE execute → ${request.url}")
        val response = client.newCall(request).execute()
        Log.d("HermesApi", "SSE response HTTP ${response.code}")
        if (!response.isSuccessful) {
            throw java.io.IOException("HTTP ${response.code}: ${response.body?.string().orEmpty()}")
        }
        val source = response.body!!.source()
        var eventType: String? = null
        var lineCount = 0
        var dataEventCount = 0 // 实际收到的 data: 事件数，用于检测空流
        while (true) {
            val line = source.readUtf8Line() ?: break
            lineCount++
            if (line.isEmpty()) continue
            if (line.startsWith(":")) continue // SSE 注释 / 心跳
            if (line.startsWith("event:")) {
                eventType = line.substring(6).trim()
                continue
            }
            if (line.startsWith("data:")) {
                val data = line.substring(5).trim()
                if (data == "[DONE]") {
                    Log.d("HermesApi", "SSE received [DONE] after $lineCount lines, $dataEventCount data events")
                    return
                }
                dataEventCount++
                if (lineCount <= 5) Log.d("HermesApi", "SSE line: $line")
                onEvent(eventType, data)
                eventType = null
            }
        }
        Log.d("HermesApi", "SSE stream ended after $lineCount lines, $dataEventCount data events (readUtf8Line returned null)")
        // 空流检测：连接被关闭但没收到任何 data: 行，说明是异常断开而非正常结束。
        // 抛出异常走 onError 路径触发重试，而不是被当成成功（onDone → finalizeTurn 删除占位消息）。
        if (dataEventCount == 0) {
            throw java.io.IOException("SSE stream ended without any data")
        }
    }

    // ===================== 请求构造 =====================

    private fun buildRequest(url: String, apiKey: String, body: String): Request =
        Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

    private fun buildBody(model: String, messages: List<ChatMessage>): String {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        return JSONObject().apply {
            put("model", model.ifBlank { "hermes-agent" })
            put("stream", true)
            put("messages", arr)
        }.toString()
    }

    // ===================== URL 归一化 =====================

    private fun normalizeChatCompletionsUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isBlank()) return u
        if (u.endsWith("/chat/completions")) return u
        if (u.endsWith("/v1")) return "$u/chat/completions"
        if (u.contains("/v1")) {
            u = u.substringBefore("/v1") + "/v1"
            return "$u/chat/completions"
        }
        return "$u/v1/chat/completions"
    }

    private fun normalizeRunsUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.endsWith("/runs")) return u
        u = if (u.contains("/v1")) u.substringBefore("/v1") + "/v1" else "$u/v1"
        return "$u/runs"
    }

    private fun normalizeRunEventsUrl(raw: String, runId: String): String {
        var u = raw.trim().trimEnd('/')
        u = if (u.contains("/v1")) u.substringBefore("/v1") + "/v1" else "$u/v1"
        return "$u/runs/$runId/events"
    }

    // ===================== 字段解析 =====================

    private fun parseContentDelta(data: String): String? {
        return try {
            val obj = JSONObject(data)
            val choices = obj.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return null
            delta.opt("content") as? String
        } catch (_: Exception) {
            null
        }
    }

    private fun parseThinkingDelta(data: String): String? {
        return try {
            val obj = JSONObject(data)
            val choices = obj.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return null
            (delta.opt("reasoning_content") as? String)
                ?: (delta.opt("reasoning") as? String)
                ?: (delta.opt("thinking") as? String)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseToolProgress(data: String): ToolProgressEvent? = try {
        val o = JSONObject(data)
        ToolProgressEvent(
            id = o.optString("id", UUID.randomUUID().toString()),
            emoji = o.optString("emoji", "\uD83D\uDD27"),
            title = o.optString("title", "工具调用"),
            status = o.optString("status", "started"),
            preview = o.optString("preview", "")
        )
    } catch (_: Exception) {
        null
    }

    private fun parseApproval(data: String): ApprovalRequest? =
        try {
            val obj = JSONObject(data)
            val id = obj.optString("id", UUID.randomUUID().toString())
            val title = obj.optString("title", "需要你的确认")
            val detail = obj.optString("detail", "")
            val arr = obj.optJSONArray("options") ?: JSONArray()
            val opts = (0 until arr.length())
                .mapNotNull { i -> arr.optString(i, "").takeIf { it.isNotBlank() } }
            if (opts.isEmpty()) null else ApprovalRequest(id, title, detail, opts)
        } catch (_: Exception) {
            null
        }
}
