# Hermes 对话 UI · 开发交付清单（Design → Android 映射）

> 面向**修改程序 UI 的对话 / 开发智能体**。配套设计物：`hermes-ui-prototype.html`（交互原型）、`HERMES_UI_DESIGN_SPEC.md`（规范）。
> 本清单把设计 token 与组件**直接映射到现有 Android 资源与源码文件**，可直接照做。

---

## 0. 怎么"交付"给同空间的其他对话（机制）
- 交付物已在**工程根目录**，同 workspace 的任意对话/智能体用路径直接读取即可，无需手动发送：
  - `hermes-ui-prototype.html`
  - `HERMES_UI_DESIGN_SPEC.md`（本文件）
- 设计约定已写入 workspace 记忆（`.workbuddy/memory/2026-07-14.md` + `MEMORY.md`），新对话自动带上下文。
- 推荐：开启新对话做实现时，第一句让它"读取 `HERMES_UI_DEV_HANDOFF.md` 并按清单修改 UI"，即可无缝衔接。

---

## 1. 设计 Token → Android 资源映射

现有 `app/src/main/res/values/colors.xml`（日）与 `values-night/colors.xml`（夜）**已覆盖大部分 token**，直接复用：

| 设计 Token | 现有资源（日 / 夜） | 备注 |
|------------|---------------------|------|
| Primary 蓝 | `tg_blue` #007AFF / #0A84FF | ✅ 已存在，与原型一致 |
| 接收气泡 | `bubble_in_bg` #E9E9EB / #2C2C2E | ✅（原型略浅，可接受；如需更贴近原型改为 #F2F2F7/#1C1C1E） |
| 发出气泡 | `bubble_out_bg` | ✅ |
| 文本层级 | `text_primary` / `text_secondary` / `text_tertiary` | ✅ |
| 页面/表面 | `page_bg` / `surface` | ✅ |
| 分隔线 | `divider` | ✅ |
| 玻璃标签栏底 | （记忆中为 `tab_bar_bg` 50% 透明白） | ⚠️ 当前 colors.xml 未检出，若缺失则新增 `tab_bar_bg=#80FFFFFF`(日)/`#801C1C1E`(夜) |

### 需要**新增**的资源
| 名称 | 日 | 夜 | 用途 |
|------|----|----|------|
| `unread_red` | `#FF3B30` | `#FF453A` | 未读红点背景（原型 `--unread`） |

> 也可复用既有 `tool_chip_error`（值相同），但建议独立命名 `unread_red` 以语义清晰。

---

## 2. 头像：相邻色渐变（替代单色）

现状：`avatar_1~6` 为**单色**（如 `avatar_1=#007AFF`）。原型要求改为**相邻色相 135° 渐变**。
渐变端点沿用现有 iOS 调色板，保证与旧头像色系一致：

| 类 | 起点 → 终点（日） | 夜 |
|----|------------------|----|
| av-1 红→橙 | `#FF3B30` → `#FF9500` | `#FF453A` → `#FF9F0A` |
| av-2 绿→青 | `#34C759` → `#5AC8FA` | `#30D158` → `#64D2FF` |
| av-3 蓝→靛 | `#0A84FF` → `#5E5CE6` | `#0A84FF` → `#7D7BFF` |
| av-4 紫→品红 | `#AF52DE` → `#FF2D55` | `#BF5AF2` → `#FF375F` |
| av-5 黄绿→绿 | `#B0E000` → `#30D158` | `#C6FF00` → `#30D158` |
| av-6 青→蓝 | `#64D2FF` → `#0A84FF` | `#64D2FF` → `#0A84FF` |

### 2a. 会话列表（XML / View，ConversationsActivity）
新增 `res/drawable/av_gradient_1.xml` … `av_gradient_6.xml`：
```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
  <gradient android:type="linear" android:angle="135"
            android:startColor="#FF3B30" android:endColor="#FF9500"/>
  <corners android:radius="24dp"/>
</shape>
```
列表项 `AvatarView`（或 `ImageView`/`TextView` 圆形）background 改为 `@drawable/av_gradient_N`，按名称 hash 选取 N（沿用现有 hash 逻辑）。

### 2b. 对话界面（Compose，ChatActivity）
头像 `Box` / `Surface` 用 `Brush.linearGradient`：
```kotlin
val brush = Brush.linearGradient(
    colors = listOf(Color(0xFF0A84FF), Color(0xFF5E5CE6)),
    start = Offset(0f, 0f), end = Offset(1f, 1f)
)
Box(modifier = Modifier.background(brush, CircleShape)) { /* 首字母 */ }
```
按联系人选取对应端点对（见上表）。

---

## 3. 未读徽标：白字 + 红底

现状：列表未读计数未见专用红底样式。改为：
- 背景：`unread_red`（新增，§1）
- 文字色：**白色**（`text_on_blue` 或 `#FFFFFF`）
- 形状：圆角胶囊（`button_radius=12dp` 或全圆），最小高度 22dp，数字 `font-weight:700`

⚠️ 关键坑（原型曾踩）：徽标数字必须**显式白字**，避免被列表项 preview 文本的灰色样式继承覆盖（特异性问题）。在 XML 给该 `TextView` 直接 `android:textColor="#FFFFFF"`；Compose 里 `color = Color.White`。

---

## 4. 动效

### 4a. 神奇缩放（＋ FAB → 新会话）
最稳妥的"macOS 式放大"实现：从 FAB 位置 scale-up 启动 ChatActivity。
在 `ConversationsActivity` 的 ＋ 点击处：
```kotlin
val opt = ActivityOptionsCompat.makeScaleUpAnimation(
    fab, fab.width/2, fab.height/2, 0, 0)   // 从 FAB 中心由小放大
startActivity(intent, opt.toBundle())
```
- 时长 ~540ms；系统默认带 decelerate，已接近原型 expo-out 观感。
- 若需精确 `cubic-bezier(0.16,1,0.3,1)`，用 `ActivityOptionsCompat.makeSceneTransitionAnimation` + 自定义 `Transition`（`ChangeBounds`+`ChangeTransform`，interpolator `FastOutSlowInInterpolator`）。
- 尊重无障碍：`if (prefersReducedMotion) startActivity(intent)` 直接跳转，无动画。
- 打开后 ChatActivity 清空历史消息、标题置「新建会话」。

### 4b. 搜索框贝塞尔弹出
`ConversationsActivity` 搜索条（目前多为显隐切换）改为 height/alpha 过渡：
```kotlin
val anim = ObjectAnimator.ofPropertyValuesHolder(
    searchBar,
    PropertyValuesHolder.ofInt("height", 0, targetH),
    PropertyValuesHolder.ofFloat("alpha", 0f, 1f)
)
anim.duration = 360
anim.interpolator = FastOutSlowInInterpolator()   // ≈ cubic-bezier(0.22,1,0.36,1)
anim.start()
```
或用 `TransitionManager.beginDelayedTransition(root, AutoTransition().apply{ interpolator = FastOutSlowInInterpolator(); duration = 360 })` 配合布局 visibility 切换，更省手写。

---

## 5. 字体（⚠️ 需用户确认）
原型用 **Plus Jakarta Sans** 避开默认审美；App 当前用系统字体（iOS 风本身已协调）。
- 选项 A（推荐，零依赖）：沿用系统字体，视觉差异极小，直接跳过。
- 选项 B：引入 `Plus Jakarta Sans` 字体文件 → `res/font/`，在 `styles.xml` / Compose `Typography` 引用。
- **实现前务必向用户确认选 A 还是 B**，不要默认加字体依赖。

---

## 6. 影响文件清单
- `app/src/main/res/values/colors.xml`、`values-night/colors.xml` — 新增 `unread_red`（及可能的 `tab_bar_bg`）
- `app/src/main/res/drawable/av_gradient_1..6.xml` — 新增渐变
- `app/src/main/res/layout/activity_conversations.xml` — 列表头像 background、未读徽标白字、搜索条过渡
- `app/src/main/java/.../ui/ConversationsActivity.kt` — 头像 hash 取渐变、神奇缩放启动、搜索动画
- `app/src/main/java/.../ui/ChatActivity.kt`（Compose）— 头像 `Brush.linearGradient`、新会话清空
- （可选）`app/src/main/res/font/`、`styles.xml` / Compose `Typography` — 仅当选 B

---

## 7. 验收清单（QA）
- [ ] 未读徽标数字为**白色**，红底，日/夜模式均清晰（对比 ≥ 4.5:1）
- [ ] 头像为**相邻色渐变**（非单色），同一联系人颜色稳定
- [ ] 点击 ＋ 有明显的放大转场进入新会话；开启"减少动效"时直接跳转
- [ ] 搜索图标点击为平滑展开（非硬切）
- [ ] 玻璃标签栏、气泡、文本层级与原型视觉一致

---
*UI Designer · 开发交付 · 2026-07-14*
