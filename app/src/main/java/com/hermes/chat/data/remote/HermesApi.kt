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
        // 读超时设为 0 = 不设读超时、无限等待。长运行模式下服务端执行工具（读 config/注册表等文件）
        // 可能长时间不吐任何字节，固定读超时（原 120s）会把"正在干活"的活连接误杀。
        // 改由 pingInterval(30s) 探测"死 TCP 连接"：若对端无 pong 则 OkHttp 主动断开并走 onError 重试；
        // 真正的卡死（服务端发了 running 后再无任何事件）由 RunWatcherService 的 150s 看门狗兜底。
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS) // 整轮调用 5 分钟封顶（终极兜底，正常由看门狗/ping 触发）
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
            // Hermes 的 /v1/runs 走 Responses API 规范，请求体用 input 字段（而非 chat/completions 的 messages）
            val request = buildRequest(url, apiKey, buildRunBody(model, messages))
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                onError(java.io.IOException("HTTP ${response.code}: ${response.body?.string().orEmpty()}"))
                return@withContext
            }
            val bodyStr = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(bodyStr) }.getOrNull()
            val id = json?.let { extractRunId(it) } ?: ""
            if (id.isBlank()) {
                Log.e("HermesApi", "createRun 未解析到 run id，原始响应体=$bodyStr")
                onError(java.io.IOException("Hermes 未返回 run id（响应：${bodyStr.take(200)}）"))
            } else onRunId(id)
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
            Log.i("HermesApi", "streamLoop starting")
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
            Log.i("HermesApi", "streamLoop completed normally → onDone")
            onDone()
        } catch (e: Exception) {
            Log.i("HermesApi", "streamLoop error: ${e.javaClass.simpleName}: ${e.message}")
            onError(e)
        }
    }

    /** 读取 SSE：`event:` 设置事件类型，`data:` 回调 (eventType, data)。`data: [DONE]` 结束循环。 */
    private fun consumeSse(request: Request, onEvent: (eventType: String?, data: String) -> Unit) {
        Log.i("HermesApi", "SSE execute → ${request.url}")
        val response = client.newCall(request).execute()
        Log.i("HermesApi", "SSE response HTTP ${response.code}")
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
                    Log.i("HermesApi", "SSE received [DONE] after $lineCount lines, $dataEventCount data events")
                    return
                }
                dataEventCount++
                if (lineCount <= 5) Log.i("HermesApi", "SSE line: $line")
                // 临时诊断：打印原始 data 事件（限前 40 条、每条截断 300 字符），用于确认长运行模式响应格式
                if (dataEventCount <= 40) Log.i("HermesApi", "SSE data[$dataEventCount] evt=$eventType :: ${data.take(300)}")
                onEvent(eventType, data)
                eventType = null
            }
        }
        Log.i("HermesApi", "SSE stream ended after $lineCount lines, $dataEventCount data events (readUtf8Line returned null)")
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

    /**
     * 长运行模式（/v1/runs）请求体：Hermes 的 run 接口遵循 OpenAI Responses API 规范，
     * 用 `input` 字段承载对话历史（chat/completions 用的是 `messages`，两者不同）。
     */
    private fun buildRunBody(model: String, messages: List<ChatMessage>): String {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        return JSONObject().apply {
            put("model", model.ifBlank { "hermes-agent" })
            put("stream", true)
            put("input", arr)
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

    /**
     * 从 /v1/runs 的响应体里尽可能稳健地提取 run id，返回第一个非空且不等于字面量 "null" 的字符串；都取不到返回 ""。
     *
     * 提取顺序与兜底原因：
     * 1) 顶层 `id`：服务端最规范、最常见。但 Android 的 [org.json.JSONObject.optString]
     *    对"非 String 类型"会**直接返回默认空串**（不会 toString）。这正是本次 bug 的根因——
     *    若服务端把 id 返回为 Number 或别的对象，旧代码 `optString("id","")` 取到空便误报"未返回 run id"。
     *    因此这里用 [org.json.JSONObject.opt] 取原始值：String 直接用，其它类型用 `toString()` 兜底，绝不因类型不是 String 而丢 id。
     * 2) 顶层别名 `run_id` / `runId` / `response_id` / `task_id`：兼容不同版本/网关的命名习惯。
     * 3) 嵌套对象 `data.id` / `run.id` / `response.id` / `data.run_id` / `run.run_id`：
     *    部分实现把 id 包在 `data` / `run` / `response` 子对象里，用 [optJSONObject] 逐级取，全程空安全。
     */
    private fun extractRunId(json: JSONObject): String {
        // 1) 顶层 "id"：String 直接用，其它类型 toString 兜底
        json.opt("id")?.let { v ->
            val s = if (v is String) v else v.toString()
            if (s.isNotBlank() && s != "null") return s
        }
        // 2) 顶层常见别名（均为字符串键，同样做类型兜底）
        for (key in listOf("run_id", "runId", "response_id", "task_id")) {
            json.opt(key)?.let { v ->
                val s = if (v is String) v else v.toString()
                if (s.isNotBlank() && s != "null") return s
            }
        }
        // 3) 嵌套对象：data.id / run.id / response.id
        for (parent in listOf("data", "run", "response")) {
            json.optJSONObject(parent)?.opt("id")?.let { v ->
                val s = if (v is String) v else v.toString()
                if (s.isNotBlank() && s != "null") return s
            }
        }
        // 3) 嵌套对象：data.run_id / run.run_id
        for (parent in listOf("data", "run")) {
            json.optJSONObject(parent)?.opt("run_id")?.let { v ->
                val s = if (v is String) v else v.toString()
                if (s.isNotBlank() && s != "null") return s
            }
        }
        return ""
    }

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
        // 尝试从 name/tool/function 字段获取工具标识名，作为 title 的补充
        val toolName = o.optString("name", o.optString("tool", o.optString("function", "")))
        val rawTitle = o.optString("title", "")
        val title = when {
            rawTitle.isNotBlank() && toolName.isNotBlank() -> "$toolName · $rawTitle"
            rawTitle.isNotBlank() -> rawTitle
            toolName.isNotBlank() -> toolName
            else -> "工具调用"
        }
        ToolProgressEvent(
            id = o.optString("id", UUID.randomUUID().toString()),
            emoji = o.optString("emoji", "\uD83D\uDD27"),
            title = title,
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
