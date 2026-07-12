<template>
  <div class="chat-shell">
    <div class="chat-title">Hermes 对话</div>

    <div class="input-wrap">
      <SlashCommandPanel
        :open="sc.open"
        :groups="sc.grouped"
        :active-index="sc.activeIndex"
        @select="onSelect"
        @hover="onHover"
      />
      <textarea
        ref="ta"
        class="chat-input"
        rows="3"
        placeholder="输入 / 唤起命令…（试试 /eng、/web）"
        @input="onInput"
        @keydown="onKeydown"
        @keyup="onKeyup"
      ></textarea>
    </div>

    <p class="hint">
      输入 <code>/</code> 弹面板；<code>/</code> 后跟字符实时过滤；
      <code>↑ ↓</code> 移动、<code>Enter</code>/<code>Tab</code> 选中、<code>Esc</code> 关闭、回删到 <code>/</code> 自动收起。
    </p>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import SlashCommandPanel from './SlashCommandPanel.vue';
import { useSlashCommand } from './useSlashCommand.js';

const sc = useSlashCommand();
const ta = ref(null);

onMounted(() => sc.init());

function onInput(e) {
  sc.handleInput(e.target.value, e.target.selectionStart);
}
function onKeyup() {
  // keyup 兜底同步光标（输入法 / 移动端场景）
  if (ta.value) sc.handleInput(ta.value.value, ta.value.selectionStart);
}
function onKeydown(e) {
  const consumed = sc.handleKeydown(e);
  if (!consumed) return;
  e.preventDefault();
  if (e.key === 'Enter' || e.key === 'Tab') {
    const item = sc.filtered.value[sc.activeIndex.value];
    applySelect(item);
  }
}
function onHover(i) {
  sc.activeIndex.value = i;
}
function onSelect(item) {
  applySelect(item);
}

function applySelect(item) {
  const res = sc.select(item);
  if (!res || !res.token) return;
  insertAtCaret(res.token, res.triggerPos, res.replaceLen);
}

/** 把 token 替换掉 "/query"，并在末尾补一个空格 */
function insertAtCaret(token, triggerPos, replaceLen) {
  const el = ta.value;
  if (!el) return;
  const v = el.value;
  const start = triggerPos;
  const end = triggerPos + replaceLen;
  el.value = v.slice(0, start) + token + ' ' + v.slice(end);
  const pos = start + token.length + 1;
  el.focus();
  el.setSelectionRange(pos, pos);
  el.dispatchEvent(new Event('input')); // 通知外部（草稿保存等）
}
</script>

<style scoped>
.chat-shell { max-width: 480px; margin: 40px auto; padding: 0 16px; }
.chat-title { font-weight: 700; font-size: 18px; margin-bottom: 12px; }
.input-wrap { position: relative; }
.chat-input {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid #e6e8eb;
  border-radius: 14px;
  padding: 12px;
  font: 15px/1.5 system-ui, sans-serif;
  resize: vertical;
}
.hint { margin-top: 12px; font-size: 12px; color: #8a8f98; line-height: 1.6; }
.hint code {
  background: #f0f2f5; border-radius: 4px; padding: 1px 5px; font-size: 11px;
}
@media (prefers-color-scheme: dark) {
  .chat-input { background: #1c1c1e; color: #f2f2f7; border-color: #3a3a3c; }
  .hint code { background: #2c2c2e; }
}
</style>
