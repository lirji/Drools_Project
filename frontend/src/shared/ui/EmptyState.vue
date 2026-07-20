<script setup lang="ts">
/**
 * 空/闲置态（重设计）：图标 + 标题 + 提示 + 可选操作，替代全站散落的一行灰字 .empty/.idle/.muted。
 */
defineProps<{ icon?: string; title: string; hint?: string }>()
</script>

<template>
  <div class="empty-state">
    <div class="icon">{{ icon || '◍' }}</div>
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
.icon { font-size: 32px; color: var(--text-faint); }
.title { font-size: var(--fs-md); font-weight: var(--fw-medium); color: var(--text); }
.hint { font-size: var(--fs-sm); color: var(--text-soft); max-width: 42ch; }
.action { margin-top: var(--sp-2); }
</style>
