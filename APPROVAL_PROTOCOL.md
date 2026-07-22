# Hermes 审批消息对接协议（后端侧）

> 适用：当 Hermes Agent 需要用户批准执行某条命令（如被安全策略拦截的 `curl`、
> 危险操作等）时，应通过**结构化 SSE 事件**通知客户端，而非把"需要批准"写进普通
> 助手文本。本文档描述客户端期望的精确格式与回传约定。

## 结论先行

- **客户端无需改动、无需重装 APK。** 现有 Android 客户端已完整支持审批事件的
  接收、渲染与回传（代码路径已验证）。
- 根本修复只需 **Hermes Agent 后端**在需要批准时下发 `event: approval_request`
  结构化事件，并保持回合等待用户回传。

---

## 1. 客户端识别审批的两条路径（已实现）

| 路径 | 触发条件 | 说明 |
|------|----------|------|
| ① 结构化事件 | SSE 收到 `event: approval_request` + 合法 `data` | **首选**，自动弹带按钮的审批卡 |
| ② 文本兜底 | 助手正文命中 `/approve`、`/approve session`、`/deny`、`/reject` 中 **≥2 个不同** 选项 | 仅在①未触发时启用，误判风险高，**不依赖此路径** |

路径①优先：一旦收到 `approval_request`，`RunWatcherService.runWatcher` 会置
`approvalHandled=true`，文本兜底不再介入。

---

## 2. 结构化事件格式（后端必须下发）

在 SSE 流中追加一个**命名事件** `approval_request`：

```
event: approval_request
data: {"id":"refresh-1","title":"执行设备刷新命令","detail":"curl -X POST http://192.168.31.62:8080/api/device/70:AF:09:3A:EF:9C/refresh","options":["/approve","/deny"]}
```

### `data` 字段（JSON）

| 字段 | 必填 | 说明 |
|------|------|------|
| `id` | 否 | 审批唯一标识；缺省时客户端自动生成。建议填，便于后端对账 |
| `title` | 否 | 审批卡标题；缺省为"需要你的确认" |
| `detail` | 否 | 命令/说明正文，**会在审批卡中完整展示**；建议放入被拦截命令的完整文本 |
| `options` | 是 | 字符串数组，**至少 1 个非空**即可弹卡。建议给 `["/approve","/deny"]`，让 UI 同时有"批准"和"拒绝"按钮 |

> 客户端 `parseApproval` 只要求 `options` 非空；但给两个选项体验最佳。

### 发送时机与位置

- 可在与文本增量**同一个 SSE 连接**内混合下发，客户端 `streamLoop` 按 `eventType`
  分发：
  ```
  event: message.delta
  data: {"choices":[{"delta":{"content":"刷新命令被安全策略拦截，等待你批准。"}}]}

  event: approval_request
  data: {"id":"refresh-1","title":"执行设备刷新命令","detail":"curl -X POST http://192.168.31.62:8080/api/device/70:AF:09:3A:EF:9C/refresh","options":["/approve","/deny"]}

  ```
- 事件下发后，该回合以标准 `data: [DONE]` 结束，把"等待批准"状态交还给客户端。

---

## 3. 回传约定（已验证的客户端行为）

用户在审批卡点击某选项 → 客户端调用 `ChatViewModel.respondToApproval(id, option)`：

1. 将该审批消息状态置为 `RESOLVED`（UI 显示已处理）；
2. 调用 `sendUserMessage(option)`，即把 **option 字符串本身**作为一条新的
   **user 消息**发往 `POST {baseUrl}/v1/chat/completions`。

即：点「批准」(option=`/approve`) → 后端新回合收到一条内容为 `/approve` 的用户消息；
点「拒绝」(option=`/deny`) → 收到 `/deny`。

### 后端必须处理回传

- 收到 `/approve` 时，应识别它对应**上一条审批**（可用 `id` 在上下文中关联），
  然后**执行被拦截的命令**（即那个 `curl`），并返回执行结果。
- 收到 `/deny` 时，取消执行并回复已拒绝。
- **不要在发 `approval_request` 的回合里预先执行命令**，也不要仅用自然语言文本
  告知"需要批准"——否则客户端不会弹框、回传机制也不生效。

---

## 4. 反例 · 本次问题根因

当前 Agent 把审批写成**普通助手文本**下发：

```
刷新命令刚才被安全策略拦截了，需要你批准一下：
curl -X POST http://192.168.31.62:8080/api/device/70:AF:09:3A:EF:9C/refresh
```

→ 无 `approval_request` 事件；文本中无 `/approve` 等指令（仅 curl 的 URL 路径
`/api/...` 和中文"批准"）→ 文本兜底不匹配 → 客户端当普通消息显示，**无审批卡**。

---

## 5. 对接检查清单

- [ ] 需要用户批准时下发 `event: approval_request`（而非自然语言文本）
- [ ] `data.options` 至少含 `/approve` 与 `/deny` 两个
- [ ] `detail` 放入被拦截命令的完整文本
- [ ] 事件后回合以 `data: [DONE]` 结束，进入"等待批准"状态
- [ ] 新回合收到 `/approve` → 执行对应命令；收到 `/deny` → 取消
- [ ] （可选）`id` 带上，便于审计与重试对账

> 注：用户已收到的那条历史消息已作为普通文本入库，改协议后不会回溯变成审批卡；
> 修复仅对**之后的**审批请求生效。
