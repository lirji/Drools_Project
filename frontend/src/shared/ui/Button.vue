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
  border: 1px solid var(--border-ctl); border-radius: var(--radius-sm);
  background: var(--bg-elev); color: var(--text); cursor: pointer; text-decoration: none;
  font-size: var(--fs-sm); font-weight: var(--fw-medium); font-family: inherit;
  /* 顶部 1px 内高光：让控件"有厚度"。暗色下这是最经济、最不容易翻车的立体手法（Raycast 的做法）。 */
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .06);
  transition: background var(--dur-fast) var(--ease-out), border-color var(--dur-fast) var(--ease-out),
              transform var(--dur-fast) var(--ease-out);
}
.btn.md { padding: var(--sp-2) var(--sp-4); }
.btn.sm { padding: var(--sp-1) var(--sp-3); font-size: var(--fs-xs); }
.btn:hover { background: var(--bg-hover); border-color: var(--border-strong); }
.btn:active:not(:disabled) { transform: translateY(1px); }
.btn:disabled { opacity: .55; cursor: not-allowed; }
/* 主按钮：竖向渐变 + 顶部内高光。辉光只在 hover 出现——常驻辉光会把一屏的注意力配额烧光。 */
.btn.primary {
  background: linear-gradient(180deg, var(--accent-hover), var(--accent));
  border-color: transparent; color: var(--text-invert);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .22);
}
.btn.primary:hover {
  background: linear-gradient(180deg, var(--accent-hover), var(--accent-hover));
  border-color: transparent;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .28), var(--glow);
}
.btn.subtle { background: var(--bg-soft); border-color: var(--border); }
.btn.subtle:hover { background: var(--bg-hover); border-color: var(--border-strong); }
.btn.danger { color: var(--err); border-color: color-mix(in srgb, var(--err) 40%, transparent); background: var(--err-soft); }
.btn.danger:hover { background: var(--err); color: var(--text-invert); border-color: var(--err); }
/* 触屏没有 hover，功能性反馈必须同时给 :active。 */
@media (hover: none) {
  .btn.primary:active:not(:disabled) { box-shadow: inset 0 1px 0 rgba(255, 255, 255, .28), var(--glow); }
}
@media (pointer: coarse) { .btn { min-height: var(--touch-min); } }
</style>
