<script setup lang="ts">
/**
 * 徽章/标签原语（重设计）：收敛散落的状态 pill / 绑定 tag / method 圆点。kind 映射到语义 -soft 底 + 主色字。
 */
withDefaults(defineProps<{
  kind?: 'neutral' | 'ok' | 'warn' | 'err' | 'blue' | 'accent'
  /**
   * PR-2 新增：状态的**几何形**。状态不能只靠颜色编码——色觉障碍、灰度打印、
   * 以及"一屏 20 行里用余光扫"这三种场景下，颜色都是不可靠的唯一通道。
   * 不传时行为与改造前完全一致（无形状标记），故对既有调用点零影响。
   */
  shape?: 'none' | 'dot' | 'square' | 'triangle' | 'ring' | 'hatch'
}>(), { kind: 'neutral', shape: 'none' })
</script>

<template>
  <span class="badge" :class="[kind, shape !== 'none' ? 's-' + shape : '']"><slot /></span>
</template>

<style scoped>
.badge {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 2px var(--sp-2); border-radius: var(--radius-pill);
  font-size: var(--fs-xs); font-weight: var(--fw-medium); line-height: 1.6;
  background: var(--bg-soft); color: var(--text-soft); border: 1px solid var(--border);
}
.badge.ok { background: var(--ok-soft); color: var(--ok); border-color: transparent; }
.badge.warn { background: var(--gold-soft); color: var(--gold); border-color: transparent; }
.badge.err { background: var(--err-soft); color: var(--err); border-color: transparent; }
.badge.blue { background: var(--blue-soft); color: var(--blue); border-color: transparent; }
.badge.accent { background: var(--accent-soft); color: var(--accent); border-color: transparent; }

/* ── 形状标记：与颜色正交的第二编码通道 ── */
.badge[class*="s-"]::before { content: ""; flex: none; width: 9px; height: 9px; }
.badge.s-dot::before { border-radius: 50%; background: currentColor; }
.badge.s-square::before { border-radius: 1px; background: currentColor; }
.badge.s-ring::before { border-radius: 50%; border: 1.5px solid currentColor; }
.badge.s-triangle::before {
  width: 0; height: 0; background: none;
  border-left: 5px solid transparent; border-right: 5px solid transparent;
  border-bottom: 9px solid currentColor;
}
.badge.s-hatch::before {
  background: repeating-linear-gradient(45deg, currentColor 0 1.5px, transparent 1.5px 3.5px);
  outline: 1px solid currentColor; outline-offset: -1px;
}
</style>
