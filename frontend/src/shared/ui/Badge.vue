<script setup lang="ts">
/**
 * 徽章/标签原语（重设计）：收敛散落的状态 pill / 绑定 tag / method 圆点。kind 映射到语义 -soft 底 + 主色字。
 */
withDefaults(defineProps<{ kind?: 'neutral' | 'ok' | 'warn' | 'err' | 'blue' | 'accent' }>(), { kind: 'neutral' })
</script>

<template>
  <span class="badge" :class="kind"><slot /></span>
</template>

<style scoped>
.badge {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 2px var(--sp-2); border-radius: var(--radius-pill);
  font-size: var(--fs-xs); font-weight: var(--fw-medium); line-height: 1.6;
  background: var(--bg-soft); color: var(--text-soft); border: 1px solid var(--border);
}
.badge.ok { background: var(--ok-soft); color: var(--ok); border-color: transparent; }
.badge.warn { background: var(--gold-soft); color: var(--gold); border-color: transparent; }
.badge.err { background: var(--err-soft); color: var(--err); border-color: transparent; }
.badge.blue { background: var(--blue-soft); color: var(--blue); border-color: transparent; }
.badge.accent { background: var(--accent-soft); color: var(--accent); border-color: transparent; }
</style>
