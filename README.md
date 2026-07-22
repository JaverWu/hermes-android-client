> 初衷：受够了那种不能打开即用的不顺畅感，所以自己开发了一个Hermes的对话客户端；用法：hermes里面的channel选项下面有一个Api server，填写好api key（自己随便设置）；然后填入这个安卓客户端就行了！仅在VIVO X100pro测试通过

# Hermes 对话（Android）

纯AI开发，结合Hermes的channel提供的api server开放接口，可以连接hermes，从而提供更流畅、更简单的对话体验

一个面向 **Android 10.0（API 29）及以上** 的客户端 App，用于和 Hermes 后端进行**流式对话**，
并在 Hermes 给出操作选项（如 `/approve`、`/approve session`、`/deny`）时**一键点击确认**，
同时用本地数据库**记录并恢复历史对话**。

## 功能

- 🔁 **流式对话**：基于 OpenAI 兼容的 `POST /v1/chat/completions` SSE 流式接口，逐字渲染回复。
- ✅ **审批一键确认**：后端下发的「审批请求」会被解析成可点击按钮；点击后把对应指令（如 `/approve`）
  当作一条用户消息回传给对话流，继续交互。
- 💾 **对话记忆**：使用 Room 本地数据库持久化「会话列表 + 消息」，支持新建 / 切换 / 删除会话，
  关闭 App 后再次打开可恢复历史。
- ⚙️ **可配置连接**：API Base URL、API Key、模型名、系统提示词均在 App 内「设置」页填写。

## 食用方法
### 进入hermes webui界面的channels下的api srever： <img width="1105" height="594" alt="image" src="https://github.com/user-attachments/assets/b68d0cff-d622-4a4b-86bb-0d89b9abb26a" />
### api server填写方法： <img width="502" height="681" alt="image" src="https://github.com/user-attachments/assets/6b17bd8e-db16-4a95-bf13-a5aa5eb06281" />
### 手机客户端填写方法： <img width="311" height="467" alt="image" src="https://github.com/user-attachments/assets/5d95a57a-9828-4daf-b5d7-2be3bb6fa300" />


## 构建与运行

> ⚠️ 本仓库在一台没有 Android SDK 的机器上生成，未做编译验证。请用 **Android Studio（Hedgehog / Iguana 及以上）** 打开并构建。

1. 用 Android Studio 打开本目录（`File → Open`，选择 `安卓版的hermes`）。
2. Android Studio 会自动根据 `gradle/wrapper` 下载 Gradle 8.9，并同步依赖（需联网）。
3. 连接一台 Android 10+ 设备或启动模拟器（API 29+）。
4. 点击 ▶ Run 安装运行。
   - 若你更习惯命令行：`./gradlew assembleDebug`（macOS / Linux）或 `gradlew.bat assembleDebug`（Windows）。

**环境要求**：Android SDK Platform 34、Build-Tools 34.x、JDK 17（Android Studio 自带）。

## 配置后端

首次进入后，打开右上角「设置」填写。设置页顶部提供**连接模式**选择，二选一：

### 连接模式：使用Hermes内部的API Server

向 Hermes 的 OpenAI 兼容 SSE 接口流式请求，回复直接渲染在 App 内，支持审批一键确认与历史恢复。需填写：

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| API 地址 | Hermes 的 OpenAI 兼容接口根地址 | `https://your-hermes-host/v1` |
| API 密钥 | `Authorization: Bearer <key>` | `这里填入api key` |
| 模型名称 | 请求体里的 `model` 字段 | `hermes-agent` |
| 系统提示词 | 可选，作为 `system` 角色注入 | — |

> 应用会向 `{BaseURL}/chat/completions` 发送请求。

## 目录结构

```
app/src/main/java/com/hermes/chat/
├── HermesApplication.kt            # Application，提供 Room 数据库
├── data/
│   ├── model/                     # ChatMessage / ApprovalRequest
│   ├── local/                     # Room 实体、DAO、AppDatabase
│   ├── preferences/               # SettingsRepository（连接配置）
│   └── remote/                    # HermesApi（SSE 流式）、ApprovalDetector
└── ui/
    ├── chat/                      # ChatActivity + ChatViewModel + MessageAdapter
    ├── conversations/             # 会话列表
    └── settings/                  # 设置页
```

## 备注

- `android:usesCleartextTraffic="true"` 已开启，方便连接本地 HTTP 调试后端；正式发布前建议关闭或改用 HTTPS。
- 流式读取不设读超时（`readTimeout=0`），以兼容长时间生成。
- 审批卡片与系统提示不会进入发给后端的对话历史；回传的 `/approve` 等指令会以 `user` 角色进入历史。
