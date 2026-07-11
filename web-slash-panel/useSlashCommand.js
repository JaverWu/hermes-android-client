// useSlashCommand.js
// 斜杠命令面板核心逻辑（与 UI 无关），在 Vue 组件里通过 useSlashCommand() 使用。
//
// 接入点（在哪里捕获按键 / 读数据源）：
//   - 输入框的 @input / @keydown / @keyup 事件 -> 调 handleInput / handleKeydown
//   - 数据源就近从 commands.js 读取，init() 里静态优先、再异步覆盖为后端数据
//
// 用法：
//   const sc = useSlashCommand();
//   onMounted(() => sc.init());
//   <textarea @input="onInput" @keydown="onKeydown" @keyup="onKeyup" />
//   function onInput(e) { sc.handleInput(e.target.value, e.target.selectionStart); }

import { ref, computed } from 'vue';
import { getStaticCommands, fetchCommands } from './commands.js';

export function useSlashCommand() {
  const allCommands = ref([]);
  const open = ref(false);
  const query = ref('');
  const activeIndex = ref(0);
  let triggerPos = -1; // 触发符 "/" 在文本中的位置，用于插入时替换

  /** 按名称 + 描述 + 关键词做「描述即搜索」 */
  const filtered = computed(() => {
    const q = query.value.trim().toLowerCase();
    if (!q) return allCommands.value;
    return allCommands.value.filter((c) =>
      [c.title, c.description, ...(c.keywords || [])]
        .join(' ')
        .toLowerCase()
        .includes(q)
    );
  });

  /** 按分组折叠，供面板渲染分隔标题 */
  const grouped = computed(() => {
    const map = new Map();
    for (const c of filtered.value) {
      if (!map.has(c.groupLabel)) map.set(c.groupLabel, []);
      map.get(c.groupLabel).push(c);
    }
    return [...map.entries()].map(([label, items]) => ({ label, items }));
  });

  /** 初始化数据源：静态优先，随后用后端数据覆盖 */
  function init() {
    allCommands.value = getStaticCommands();
    fetchCommands()
      .then((list) => { allCommands.value = list; })
      .catch(() => { /* 后端不可用时保留静态数据 */ });
  }

  /**
   * 处理输入变化
   * @param {string} value 文本框全文
   * @param {number} caret 光标位置
   */
  function handleInput(value, caret) {
    const before = value.slice(0, caret);
    const slashIdx = before.lastIndexOf('/');
    // 触发条件："/" 在行首或前一个是空白，且 "/" 之后还没有空格（空格视为普通文本）
    const okTrigger =
      slashIdx !== -1 &&
      (slashIdx === 0 || /\s/.test(before[slashIdx - 1])) &&
      !before.slice(slashIdx + 1).includes(' ');

    if (okTrigger) {
      triggerPos = slashIdx;
      query.value = before.slice(slashIdx + 1);
      activeIndex.value = 0;
      open.value = true;
    } else {
      open.value = false;
      triggerPos = -1;
    }
  }

  function move(delta) {
    const len = filtered.value.length;
    if (!len) return;
    activeIndex.value = (activeIndex.value + delta + len) % len;
  }

  function close() {
    open.value = false;
    query.value = '';
    triggerPos = -1;
  }

  /**
   * 选中某项：返回插入信息（由调用方写入 textarea）。
   * 若 item.action 存在则直接执行并关闭，返回 null。
   * @returns {{token:string, triggerPos:number, replaceLen:number}|null}
   */
  function select(item) {
    if (!item) return null;
    if (item.action) {
      item.action();
      close();
      return null;
    }
    const token = item.token || `/${item.title}`;
    const result = { token, triggerPos, replaceLen: query.value.length + 1 }; // +1 去掉 "/"
    close();
    return result;
  }

  /**
   * 键盘事件处理。返回 true 表示已被面板消费（调用方应 preventDefault）。
   * 注意：Enter / Tab 的「选中」动作由调用方执行（避免与输入法冲突）。
   */
  function handleKeydown(e) {
    if (!open.value) return false;
    switch (e.key) {
      case 'ArrowDown': move(1); return true;
      case 'ArrowUp':   move(-1); return true;
      case 'Enter':
      case 'Tab':       return true;
      case 'Escape':    close(); return true;
      default:
        // 删到 "/" 之前（query 已空）自动关闭
        if (e.key === 'Backspace' && query.value === '') close();
        return false;
    }
  }

  return {
    open,
    query,
    activeIndex,
    filtered,
    grouped,
    init,
    handleInput,
    handleKeydown,
    select,
    close,
    move,
  };
}
