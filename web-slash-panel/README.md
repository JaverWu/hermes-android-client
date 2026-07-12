# Hermes 对话客户端 · 斜杠命令面板（Slash Command Panel）

轻量、零重依赖的 Vue 3 斜杠命令面板。输入框输入 `/` 即弹出浮层，按名称/描述实时过滤，
键盘可达、移动端可用、深/浅色主题自适应。

---

## 一、整体方案

### 1. 数据流

```
用户输入 (textarea)
   │  @input / @keyup
   ▼
useSlashCommand.handleInput(value, caret)   ← 检测 "/" 触发 + 提取过滤词 query
   │
   ├─ open=true, query="/后字符"
   ▼
filtered = filter(数据源, query)            ← 名称 + 描述 + 关键词（描述即搜索）
grouped  = groupBy(filtered, groupLabel)    ← 分组（Skills / 工具 / 内置命令 / 模板）
   │
   ▼
SlashCommandPanel (presentational)          ← 渲染浮层、高亮、分组、滚动
   │  @select / @hover
   ▼
applySelect(item) → useSlashCommand.select(item)
   ├─ 有 token → 返回 {token, triggerPos, replaceLen}
   │              → insertAtCaret() 替换 "/query" 并补空格
   └─ 有 action → 直接执行回调（如清空对话）后关闭
```

### 2. 组件结构

| 文件 | 角色 | 说明 |
|------|------|------|
| `commands.js` | 数据源（纯 JS） | 静态命令数组 + `getStaticCommands()` / `fetchCommands()`（后端占位）。**可配置、可替换**。 |
| `useSlashCommand.js` | 逻辑（composable） | 触发检测、过滤、分组、键盘导航、选中/插入。**与 UI 无关**，可在任意框架复用。 |
| `SlashCommandPanel.vue` | 视图（presentational） | 只负责渲染浮层：分组标题、高亮项、滚动、主题。通过 props 接收 `open/groups/activeIndex`，emit `select/hover`。 |
| `App.vue` | 接入示例 | 演示如何把 textarea 与上面三者接起来（输入框、按键捕获、光标插入）。 |
| `demo.html` | 零构建 Demo | 单文件、CDN 引入 Vue，直接双击打开即可看效果。 |

> 设计原则：**数据 / 逻辑 / 视图 三层分离**。改数据源只动 `commands.js`；改交互只动 `useSlashCommand.js`；改样式只动 `SlashCommandPanel.vue`。

---

## 二、数据结构（`commands.js`）

```js
/** @typedef {Object} SlashCommand
 *  id          唯一 id
 *  group       'skill' | 'tool' | 'command' | 'template'
 *  groupLabel  分组标题（面板里的分隔标题）
 *  title       显示名
 *  description 一句话说明（参与「描述即搜索」）
 *  icon        emoji（零依赖）
 *  token       插入文本，如 "/skill:english-frame"（可选）
 *  keywords    额外搜索词（可选）
 *  action      选中即触发的函数（与 token 二选一，可选）
 */
```

四类数据源（均可被后端替换）：
1. **Skills** —— `~/.hermes/skills/` 下的已安装 skill（name + 描述）
2. **工具/能力** —— 当前会话加载的 tools（web 搜索、终端、文件、画图、定时任务…）
3. **内置命令** —— 创建定时任务、保存记忆、调用 skill、清空对话…
4. **提示词模板** —— 用户收藏的模板

---

## 三、接入点（你要在哪里接）

### ① 在哪里捕获按键事件
在输入框上挂三个事件，转发给 composable：

```vue
<textarea
  @input="onInput"
  @keydown="onKeydown"
  @keyup="onKeyup"
></textarea>

function onInput(e)  { sc.handleInput(e.target.value, e.target.selectionStart); }
function onKeyup()   { sc.handleInput(ta.value.value, ta.value.selectionStart); } // 光标兜底
function onKeydown(e){
  const consumed = sc.handleKeydown(e);
  if (!consumed) return;
  e.preventDefault();
  if (e.key === 'Enter' || e.key === 'Tab') {
    applySelect(sc.filtered.value[sc.activeIndex.value]);
  }
}
```

- `/` 触发：光标前是 `/`，且 `/` 在行首或前一个字符是空白，且 `/` 后还没有空格。
- `↑ ↓` 移动高亮、`Enter`/`Tab` 选中、`Esc` 关闭、回删到 `/` 自动收起。

### ② 从哪里读取 skills / tools 列表
数据源全在 `commands.js`。当前是静态数组；接后端时把 `fetchCommands()` 里的
`setTimeout(...)` 换成真实请求即可（见文件内 TODO 注释与端点清单）：

```
GET /v1/skills        -> [{ name, description }]           （~/.hermes/skills）
GET /v1/session/tools -> [{ id, name, description, icon }] （当前会话 tools）
GET /v1/commands      -> [{ id, name, description, icon }] （内置命令）
GET /v1/prompts       -> [{ id, name, description }]       （收藏模板）
```

`useSlashCommand().init()` 里已经做了「静态优先、再异步覆盖为后端数据」，你只需在
`init()` 里 `fetchCommands()` 返回真实数组即可，UI 无需改动。

### ③ 选中后行为
- 有 `token`：把 `/query` 替换成 token 并在末尾补空格（`insertAtCaret`）。
- 有 `action`：直接执行回调（如「清空对话」弹确认框）后关闭。
- 支持「描述即搜索」：过滤时同时匹配 `title` + `description` + `keywords`。

### ④ 样式 / 主题
- 浮层跟随输入框定位（`.input-wrap { position: relative }` + 面板 `position:absolute`）。
- 用 CSS 变量（`--slash-bg` 等），默认浅色，`@media (prefers-color-scheme: dark)` 自动深色；
  也可由外层 `data-theme="dark"` 覆盖变量。
- 列表过长 `max-height + overflow-y:auto` 滚动；分组之间有标题分隔。

---

## 四、怎么跑起来

- **最快看效果**：浏览器直接打开 `demo.html`（需联网取 Vue CDN）。
- **进项目工程**：用 Vite 等构建工具，`npm i vue` 后 `import App.vue`，
  把 `<App/>` 放进你的聊天页即可；数据源/逻辑/视图三个文件原样复用。

> 零重依赖：除 Vue 3 本身外，不引入任何第三方库。
