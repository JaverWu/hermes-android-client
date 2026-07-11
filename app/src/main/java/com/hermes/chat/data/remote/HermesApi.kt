package com.hermes.chat.data.remote

import com.hermes.chat.data.model.ApprovalRequest
import com.hermes.chat.data.model.ChatMessage
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
 * Hermes 后端客户端：以 OpenAI 兼容的 SSE 方式流式调用 /v1/chat/completions。
 *
 * 约定：
 *  - 普通内容增量：标准 OpenAI SSE（`data: {"choices":[{"delta":{"content":"..."}}]}`，以 `data: [DONE]` 结束）。
 *  - 思考流（reasoning）：部分模型在 delta 中携带 `reasoning_content` / `reasoning` / `thinking` 字段，
 *    用于实时展示 AI 的"思考过程"（类比 TUI 的实时思考流）。
 *  - 命名事件（状态）：Hermes 可能通过 `event: <type>` 下发结构化状态（如 tool 调用、run 进度等），
 *    统一回调到 [onStatus]，由上层决定是否展示。
 *  - 审批请求：结构化 SSE 命名事件
 *        event: approval_request
 *        data: {"id":...,"title":...,"detail":...,"options":[...]}
 *    若后端未下发结构化事件，也会对助手正文做文本兜底检测（见 [ApprovalDetector]）。
 */
class HermesApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // 流式读取，不设置读超时
        .build()

    suspend fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String = "hermes-agent",
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit = {},
        onStatus: (eventType: String, data: String) -> Unit = { _, _ -> },
        onApproval: (ApprovalRequest) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            // 归一化 Base URL：
            // 用户可能填 http://host:port / .../v1 / .../v1/chat/completions 任意一种，
            // 统一规整成 .../v1/chat/completions，避免重复拼接导致 500。
            val url = normalizeChatCompletionsUrl(baseUrl)

            val bodyJson = JSONObject().apply {
                // 始终带 model 字段（默认 "hermes-agent"，与 Hermes /v1/models 返回值一致）
                put("model", model.ifBlank { "hermes-agent" })
                put("stream", true)
                put(
                    "messages",
                    JSONArray().also { arr ->
                        messages.forEach { m ->
                            arr.put(
                                JSONObject()
                                    .put("role", m.role)
                                    .put("content", m.content)
                            )
                        }
                    }
                )
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "text/event-stream")
                .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = response.body?.string().orEmpty()
                onError(java.io.IOException("HTTP ${response.code}: $err"))
                return@withContext
            }

            val source = response.body!!.source()
            var eventType: String? = null
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) continue
                if (line.startsWith(":")) continue // SSE 注释 / 心跳
                if (line.startsWith("event:")) {
                    eventType = line.substring(6).trim()
                    continue
                }
                if (line.startsWith("data:")) {
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") {
                        onDone()
                        return@withContext
                    }
                    when {
                        // 结构化审批事件
                        eventType == "approval_request" ->
                            parseApproval(data)?.let { onApproval(it) }
                        // 其它命名事件（思考/工具调用/进度等）→ 状态回调
                        eventType != null ->
                            onStatus(eventType, data)
                        // 无事件的普通 chat delta
                        else -> {
                            parseContentDelta(data)?.let { onDelta(it) }
                            parseThinkingDelta(data)?.let { onThinking(it) }
                        }
                    }
                    eventType = null
                }
            }
            onDone()
        } catch (e: Exception) {
            onError(e)
        }
    }

    /**
     * 把用户填写的 Base URL 规整为标准的 OpenAI 兼容聊天端点：
     *   .../v1/chat/completions
     * 兼容以下输入：
     *   http://host:port
     *   http://host:port/
     *   http://host:port/v1
     *   http://host:port/v1/
     *   http://host:port/v1/chat/completions   （避免重复拼接）
     */
    private fun normalizeChatCompletionsUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isBlank()) return u
        // 已含完整路径则直接返回
        if (u.endsWith("/chat/completions")) return u
        // 已含 /v1 则补上 chat/completions
        if (u.endsWith("/v1")) return "$u/chat/completions"
        // 已含 /v1/ 之类（如 /v1/something）则把末尾替换
        if (u.contains("/v1")) {
            u = u.substringBefore("/v1") + "/v1"
            return "$u/chat/completions"
        }
        // 默认：当作根地址，补 /v1/chat/completions
        return "$u/v1/chat/completions"
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

    /** 解析思考流字段：reasoning_content / reasoning / thinking（不同模型字段名不同）。 */
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
