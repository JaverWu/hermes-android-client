<template>
  <div v-if="open" class="slash-panel" role="listbox" aria-label="命令面板">
    <div v-for="grp in groups" :key="grp.label" class="slash-group">
      <div class="slash-group-title">{{ grp.label }}</div>
      <button
        v-for="item in grp.items"
        :key="item.id"
        type="button"
        class="slash-item"
        :class="{ active: isActive(item.id) }"
        role="option"
        :aria-selected="isActive(item.id)"
        @mouseenter="$emit('hover', flatIndex(item.id))"
        @click="$emit('select', item)"
      >
        <span class="slash-icon">{{ item.icon }}</span>
        <span class="slash-text">
          <span class="slash-title">{{ item.title }}</span>
          <span class="slash-desc">{{ item.description }}</span>
        </span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';

const props = defineProps({
  open: { type: Boolean, default: false },
  groups: { type: Array, default: () => [] },
  activeIndex: { type: Number, default: 0 },
});
const emit = defineEmits(['select', 'hover']);

const flat = computed(() => props.groups.flatMap((g) => g.items));
function isActive(id) {
  return flat.value[props.activeIndex]?.id === id;
}
function flatIndex(id) {
  return flat.value.findIndex((c) => c.id === id);
}
</script>

<style scoped>
.slash-panel {
  position: absolute;
  left: 0;
  right: 0;
  bottom: calc(100% + 8px); /* 默认浮在输入框上方；要放下方改 top: calc(100% + 8px) 并去掉 bottom */
  max-height: 320px;
  overflow-y: auto;
  background: var(--slash-bg, #fff);
  color: var(--slash-text, #1c1c1e);
  border: 1px solid var(--slash-border, #e6e8eb);
  border-radius: 14px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.12);
  padding: 6px;
  z-index: 50;
  font: 14px/1.4 system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
  -webkit-overflow-scrolling: touch;
}
.slash-group + .slash-group { margin-top: 4px; }
.slash-group-title {
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--slash-muted, #8a8f98);
  padding: 6px 10px 2px;
}
.slash-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  text-align: left;
  background: transparent;
  border: 0;
  border-radius: 10px;
  padding: 8px 10px;
  cursor: pointer;
  color: inherit;
}
.slash-item.active { background: var(--slash-active, #3390ec); color: #fff; }
.slash-item.active .slash-desc { color: rgba(255, 255, 255, 0.85); }
.slash-icon { font-size: 18px; line-height: 1; flex: none; }
.slash-text { display: flex; flex-direction: column; min-width: 0; }
.slash-title { font-weight: 600; }
.slash-desc {
  font-size: 12px;
  color: var(--slash-muted, #8a8f98);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 深色主题适配（跟随系统；也可由外层 data-theme="dark" 覆盖） */
@media (prefers-color-scheme: dark) {
  .slash-panel {
    --slash-bg: #1c1c1e;
    --slash-text: #f2f2f7;
    --slash-border: #3a3a3c;
    --slash-muted: #8e8e93;
    --slash-active: #3390ec;
  }
}
</style>
