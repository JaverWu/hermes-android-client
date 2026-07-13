package com.hermes.chat.ui.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.hermes.chat.HermesApplication
import com.hermes.chat.R
import com.hermes.chat.data.local.ConversationEntity
import com.hermes.chat.data.local.MessageEntity
import com.hermes.chat.data.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 处理通知栏 RemoteInput 回复的 BroadcastReceiver。
 *
 * 流程：取文本 → 检查 isStreaming → 确定会话（lastConversationId 有则接上/无则新建）
 *       → 写 user+assistant 占位消息 → 启动 RunWatcherService 接管流式接收。
 *
 * 用 goAsync() 延长生命周期（最多 10 秒），避免 DB 操作未完成就被系统回收。
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REPLY = "com.hermes.chat.action.REPLY"
        const val ACTION_CLEAR_MESSAGES = "com.hermes.chat.action.CLEAR_MESSAGES"
        private const val TAG = "ReplyReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REPLY -> handleReply(context, intent)
            ACTION_CLEAR_MESSAGES -> handleClear(context)
        }
    }

    private fun handleClear(context: Context) {
        val settings = SettingsRepository(context)
        val cid = settings.lastConversationId.ifBlank { null }
        ChatNotificationManager.clearMessages(context, cid)
    }

    private fun handleReply(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(ChatNotificationManager.KEY_REPLY_TEXT)
            ?.toString()
            ?.trim()
        if (text.isNullOrEmpty()) return

        // 正在流式回复中：拒绝并提示
        if (ActiveRunState.isStreaming.value) {
            Toast.makeText(context, R.string.toast_streaming_busy, Toast.LENGTH_SHORT).show()
            return
        }

        val settings = SettingsRepository(context)
        if (!settings.isConfigured()) {
            Toast.makeText(context, R.string.toast_config_first, Toast.LENGTH_SHORT).show()
            return
        }

        // goAsync 延长 Receiver 生命周期（最多 10 秒）
        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                processReply(context, text, settings)
            } catch (e: Exception) {
                Log.e(TAG, "processReply failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun processReply(
        context: Context,
        text: String,
        settings: SettingsRepository
    ) {
        val db = (context.applicationContext as HermesApplication).database

        // 1. 确定目标会话：有 lastConversationId 且会话存在 → 接上；否则新建
        var conversationId = settings.lastConversationId
        if (conversationId.isBlank() || db.conversationDao().getById(conversationId) == null) {
            conversationId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            db.conversationDao().insert(
                ConversationEntity(
                    id = conversationId,
                    title = text.take(40),
                    createdAt = now,
                    updatedAt = now
                )
            )
            settings.lastConversationId = conversationId
        }

        // 2. 写 user 消息到 DB
        val userMsg = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = MessageEntity.ROLE_USER,
            content = text,
            createdAt = System.currentTimeMillis()
        )
        db.messageDao().insert(userMsg)

        // 3. 写 assistant 占位消息（isStreaming=true）
        val assistantId = UUID.randomUUID().toString()
        val assistantMsg = MessageEntity(
            id = assistantId,
            conversationId = conversationId,
            role = MessageEntity.ROLE_ASSISTANT,
            content = "",
            createdAt = System.currentTimeMillis(),
            isStreaming = true
        )
        db.messageDao().insert(assistantMsg)

        // 4. 更新通知为流式状态
        ChatNotificationManager.updateStreaming(context, conversationId, "Hermes 正在处理…")

        // 5. 启动 RunWatcherService 接管流式接收
        ActiveRunState.begin(assistantId)
        val serviceIntent = Intent(context, RunWatcherService::class.java).apply {
            putExtra(RunWatcherService.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(RunWatcherService.EXTRA_ASSISTANT_ID, assistantId)
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService failed: ${e.message}", e)
            db.messageDao().deleteById(assistantId)
            ActiveRunState.reset()
        }
    }
}
