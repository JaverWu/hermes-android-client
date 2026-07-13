package com.hermes.chat.ui.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.hermes.chat.HermesApplication
import com.hermes.chat.R
import com.hermes.chat.data.local.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 常驻对话通知管理器（进程级单例）。
 *
 * 双渠道设计（降低耗电）：
 * - hermes_chat_low (IMPORTANCE_LOW)：常驻对话通知 (ID=1001)，无声音无震动，用于待机/流式/完成后的消息展示
 * - hermes_run_high (IMPORTANCE_HIGH)：回复完成提醒 (ID=1002)，非 ongoing，有震动，用户可滑掉
 *
 * 通知栏对话通知生命周期：
 * - 待机时：MessagingStyle 显示最近几轮对话 + RemoteInput 输入框，用户可直接在通知栏发消息
 * - 流式时：由 RunWatcherService.startForeground 接管同一通知 ID (LOW 渠道)
 * - 完成时：更新常驻通知 (LOW) + 后台时发独立提醒通知 (HIGH, 可滑掉)
 */
object ChatNotificationManager {

    const val NOTIF_ID = 1001
    const val NOTIF_ID_ALERT = 1002
    const val KEY_REPLY_TEXT = "hermes_reply_text"
    private const val MAX_MESSAGES = 6
    private const val CHANNEL_ID_LOW = "hermes_chat_low"
    private const val CHANNEL_ID_HIGH = "hermes_run_high"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 流式更新节流：避免每个 SSE 事件都查 DB 重建通知 */
    private var lastStreamingUpdate = 0L
    private val STREAMING_UPDATE_INTERVAL = 500L

    /** 消息是否已被用户清除（点击"清除消息"后置 true，新消息到来时自动恢复） */
    @Volatile
    private var messagesCleared = false

    /**
     * 注册通知渠道。
     * - hermes_chat_low (IMPORTANCE_LOW)：常驻对话通知，无声音无震动，降低耗电
     * - hermes_run_high (IMPORTANCE_HIGH)：回复完成时的提醒通知，有震动
     */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            // LOW 渠道：常驻通知
            if (mgr.getNotificationChannel(CHANNEL_ID_LOW) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID_LOW,
                    context.getString(R.string.notification_channel_low_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_low_desc)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(channel)
            }
            // HIGH 渠道：完成提醒
            if (mgr.getNotificationChannel(CHANNEL_ID_HIGH) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID_HIGH,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_desc)
                    enableVibration(true)
                    enableLights(true)
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    // ===================== 待机状态（无流式） =====================

    /**
     * 显示/更新待机通知。异步从 DB 加载最近消息构建 MessagingStyle。
     * 从 HermesApplication.onCreate 和 RunWatcherService.finalizeTurn/handleError 调用。
     * 若 messagesCleared=true，显示无消息的干净状态。
     */
    fun showStandby(context: Context, conversationId: String?) {
        scope.launch {
            val notif = if (messagesCleared) {
                buildCleanNotification(context, conversationId)
            } else {
                buildMessagingNotification(context, conversationId, streamingText = null, alert = false)
            }
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
        }
    }

    /**
     * 清除通知中的消息内容。通知本身仍常驻，但 MessagingStyle 不再显示历史消息。
     * 下次有新消息到来时（流式/完成），自动恢复消息显示。
     */
    fun clearMessages(context: Context, conversationId: String?) {
        messagesCleared = true
        scope.launch {
            val notif = buildCleanNotification(context, conversationId)
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
        }
    }

    // ===================== 流式中 =====================

    /**
     * 为 startForeground 构建同步通知（不查 DB），满足 5 秒要求。
     * 含 RemoteInput Action，用户可在流式中看到输入框（但发送会被 Receiver 拒绝）。
     */
    fun buildForegroundNotification(
        context: Context,
        text: String,
        conversationId: String?
    ): android.app.Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID_LOW)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_logo_large)
            .addAction(buildReplyAction(context))
            .setContentIntent(buildContentIntent(context, conversationId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /**
     * 流式中更新通知。带节流（500ms），异步从 DB 加载消息重建 MessagingStyle。
     * 流式中的 assistant 消息（isStreaming=true）显示状态文案。
     * 新消息到来时自动恢复消息显示（清除 messagesCleared 标志）。
     */
    fun updateStreaming(context: Context, conversationId: String, statusText: String) {
        val now = System.currentTimeMillis()
        if (now - lastStreamingUpdate < STREAMING_UPDATE_INTERVAL) return
        lastStreamingUpdate = now
        messagesCleared = false // 有新流式消息，恢复消息显示
        scope.launch {
            val notif = buildMessagingNotification(context, conversationId, streamingText = statusText, alert = false)
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
        }
    }

    // ===================== 回复完成 =====================

    /**
     * 回复完成后更新通知。alert=true 时触发响铃+震动+悬浮通知（仅 App 在后台时）。
     * 新消息到来时自动恢复消息显示（清除 messagesCleared 标志）。
     * - 常驻通知 (ID 1001) 始终走 LOW 渠道，无声音无震动
     * - alert=true 时额外发一条 HIGH 渠道提醒通知 (ID 1002)，非 ongoing，用户可滑掉
     */
    fun showCompleted(context: Context, conversationId: String, alert: Boolean) {
        messagesCleared = false // 有新消息，恢复消息显示
        scope.launch {
            // 更新常驻通知（LOW 渠道）
            val notif = buildMessagingNotification(context, conversationId, streamingText = null, alert = false)
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
            // 后台时发一条独立提醒通知（HIGH 渠道，可滑掉）
            if (alert) {
                val alertNotif = buildAlertNotification(context, conversationId)
                NotificationManagerCompat.from(context).notify(NOTIF_ID_ALERT, alertNotif)
            }
        }
    }

    // ===================== 取消 =====================

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_ALERT)
    }

    /** 取消回复完成提醒通知（用户打开 App 时调用） */
    fun cancelAlert(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_ALERT)
    }

    // ===================== 内部构建 =====================

    private suspend fun buildMessagingNotification(
        context: Context,
        conversationId: String?,
        streamingText: String?,
        alert: Boolean
    ): android.app.Notification {
        val app = context.applicationContext as HermesApplication
        val db = app.database

        val mePerson = Person.Builder().setName("我").setKey("me").build()
        val hermesPerson = Person.Builder().setName("Hermes").setKey("hermes").build()

        val style = NotificationCompat.MessagingStyle(mePerson)
            .setConversationTitle(context.getString(R.string.app_name))

        if (conversationId != null) {
            val messages = db.messageDao().getLastNMessages(conversationId, MAX_MESSAGES)
            // getLastNMessages 返回 DESC（最新在前），需反转为 ASC 构建对话
            messages.reversed().forEach { msg ->
                when (msg.role) {
                    MessageEntity.ROLE_USER -> {
                        style.addMessage(msg.content, msg.createdAt, mePerson)
                    }
                    MessageEntity.ROLE_ASSISTANT -> {
                        if (msg.content.isNotBlank()) {
                            // 流式中的 assistant 消息：显示状态文案而非部分内容
                            val displayText = if (msg.isStreaming && streamingText != null) {
                                streamingText
                            } else {
                                msg.content.take(200)
                            }
                            style.addMessage(displayText, msg.createdAt, hermesPerson)
                        } else if (msg.isStreaming && streamingText != null) {
                            // 占位消息（内容还为空）+ 流式状态
                            style.addMessage(streamingText, msg.createdAt, hermesPerson)
                        }
                    }
                    // ROLE_SYSTEM / ROLE_APPROVAL 不在通知中显示
                }
            }
        }

        // 无消息时显示占位
        if (style.messages.isEmpty()) {
            style.addMessage(
                context.getString(R.string.empty_chat),
                System.currentTimeMillis(),
                hermesPerson
            )
        }

        return NotificationCompat.Builder(context, CHANNEL_ID_LOW)
            .setStyle(style)
            .setSmallIcon(R.drawable.ic_logo_large)
            .setContentIntent(buildContentIntent(context, conversationId))
            .addAction(buildReplyAction(context))
            .addAction(buildClearAction(context))
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /**
     * 构建无消息内容的干净通知（清除消息后显示）。
     * 仍含 RemoteInput 输入框，但不显示历史消息。
     */
    private fun buildCleanNotification(
        context: Context,
        conversationId: String?
    ): android.app.Notification {
        val mePerson = Person.Builder().setName("我").setKey("me").build()
        val hermesPerson = Person.Builder().setName("Hermes").setKey("hermes").build()

        val style = NotificationCompat.MessagingStyle(mePerson)
            .setConversationTitle(context.getString(R.string.app_name))
            .addMessage(
                context.getString(R.string.notification_clean_text),
                System.currentTimeMillis(),
                hermesPerson
            )

        return NotificationCompat.Builder(context, CHANNEL_ID_LOW)
            .setStyle(style)
            .setSmallIcon(R.drawable.ic_logo_large)
            .setContentIntent(buildContentIntent(context, conversationId))
            .addAction(buildReplyAction(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /**
     * 构建回复完成提醒通知（HIGH 渠道，非 ongoing，用户可滑掉）。
     */
    private suspend fun buildAlertNotification(
        context: Context,
        conversationId: String?
    ): android.app.Notification {
        val app = context.applicationContext as HermesApplication
        val db = app.database

        // 取最近一条 assistant 消息作为预览
        val previewText = if (conversationId != null) {
            val messages = db.messageDao().getLastNMessages(conversationId, 1)
            val lastAssistant = messages.firstOrNull { it.role == MessageEntity.ROLE_ASSISTANT }
            lastAssistant?.content?.take(100) ?: context.getString(R.string.app_name)
        } else {
            context.getString(R.string.app_name)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID_HIGH)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(previewText)
            .setSmallIcon(R.drawable.ic_logo_large)
            .setContentIntent(buildContentIntent(context, conversationId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun buildReplyAction(context: Context): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.notification_reply_label))
            .build()

        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_REPLY
        }
        // RemoteInput 要求 PendingIntent 必须是 MUTABLE（Android 12+）
        val replyPendingIntent = PendingIntent.getBroadcast(
            context, 0, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        return NotificationCompat.Action.Builder(
            R.drawable.ic_send,
            context.getString(R.string.notification_reply_label),
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()
    }

    private fun buildClearAction(context: Context): NotificationCompat.Action {
        val clearIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_CLEAR_MESSAGES
        }
        val clearPendingIntent = PendingIntent.getBroadcast(
            context, 2, clearIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(R.string.notification_clear_messages),
            clearPendingIntent
        ).build()
    }

    private fun buildContentIntent(context: Context, conversationId: String?): PendingIntent {
        val intent = Intent(context, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (conversationId != null) {
                putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)
            }
        }
        return PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
