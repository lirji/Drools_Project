<script setup lang="ts">
/**
 * 按钮原语（重设计）：收敛散落 8 文件的 .primary/.ghost/.mini/.danger。可选 to → 渲染 router-link（否则 <button>）。
 * data-testid 等属性经 $attrs 透传（fallthrough），故现有 testid 直接写在使用处即可。
 */
withDefaults(defineProps<{ variant?: 'primary' | 'ghost' | 'subtle' | 'danger'; size?: 'sm' | 'md'; to?: object }>(), {
  variant: 'ghost',
  size: 'md',
})
</script>

<template>
  <router-link v-if="to" :to="to" class="btn" :class="[variant, size]"><slot /></router-link>
  <button v-else class="btn" :class="[variant, size]"><slot /></button>
</template>

<style scoped>
.btn {
  display: inline-flex; align-items: center; justify-content: center; gap: var(--sp-2);
  border: 1px solid var(--border); border-radius: var(--radius-sm);
  background: var(--bg-elev); color: var(--text); cursor: pointer; text-decoration: none;
  font-size: var(--fs-sm); font-weight: var(--fw-medium); font-family: inherit;
  transition: background .12s ease, border-color .12s ease;
}
.btn.md { padding: var(--sp-2) var(--sp-4); }
.btn.sm { padding: var(--sp-1) var(--sp-3); font-size: var(--fs-xs); }
.btn:hover { background: var(--bg-hover); }
.btn:disabled { opacity: .55; cursor: not-allowed; }
.btn.primary { background: var(--accent); border-color: var(--accent); color: #fff; }
.btn.primary:hover { filter: brightness(1.05); background: var(--accent); }
.btn.subtle { background: var(--bg-soft); }
.btn.danger { color: var(--err); border-color: var(--err-soft); background: var(--err-soft); }
@media (pointer: coarse) { .btn { min-height: var(--touch-min); } }
</style>
