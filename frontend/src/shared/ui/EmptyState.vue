<script setup lang="ts">
/**
 * 空/闲置态（重设计）：图标 + 标题 + 提示 + 可选操作，替代全站散落的一行灰字 .empty/.idle/.muted。
 * icon 传 Icon name（如 'inbox'/'scale'）渲染内联 SVG；传未知字符串则原样回退（向后兼容旧字形调用）。
 */
import { computed } from 'vue'
import Icon from './Icon.vue'

const props = defineProps<{ icon?: string; title: string; hint?: string }>()

// 已知 Icon name 集合（与 Icon.vue 对齐的常用子集）；命中则走内联 SVG，否则回退原字符串。
const ICON_NAMES = new Set(['inbox', 'scale', 'list', 'search', 'badge-check', 'flask', 'plus', 'alert-triangle', 'info'])
const iconName = computed(() => props.icon && ICON_NAMES.has(props.icon) ? props.icon : '')
</script>

<template>
  <div class="empty-state">
    <div class="icon">
      <Icon v-if="iconName" :name="iconName" :size="30" :stroke="1.5" />
      <template v-else>{{ icon || '◍' }}</template>
    </div>
    <div class="title">{{ title }}</div>
    <div v-if="hint" class="hint">{{ hint }}</div>
    <div v-if="$slots.action" class="action"><slot name="action" /></div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex; flex-direction: column; align-items: center; text-align: center;
  gap: var(--sp-2); padding: var(--sp-7) var(--sp-4); color: var(--text-soft);
}
.icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 52px; height: 52px; margin-bottom: var(--sp-1);
  border-radius: var(--radius-lg); background: var(--bg-soft);
  font-size: 28px; color: var(--text-faint);
}
.title { font-size: var(--fs-md); font-weight: var(--fw-semibold); color: var(--text); }
.hint { font-size: var(--fs-sm); color: var(--text-soft); max-width: 42ch; }
.action { margin-top: var(--sp-2); }
</style>
