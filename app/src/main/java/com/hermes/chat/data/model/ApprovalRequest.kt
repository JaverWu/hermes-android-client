package com.hermes.chat.data.model

/**
 * 后端在 SSE 流中下发的「审批请求」结构化事件。
 *
 * 对应 SSE 形如：
 *   event: approval_request
 *   data: {"id":"r1","title":"需要执行命令","detail":"npm install","options":["/approve","/approve session","/deny"]}
 */
data class ApprovalRequest(
    val id: String,
    val title: String,
    val detail: String,
    val options: List<String>
)
