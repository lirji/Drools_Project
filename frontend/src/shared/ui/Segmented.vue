<script setup lang="ts">
/**
 * 段控原语（重设计）：收敛散落的 .seg/.chip 三处。选项可带 testid（如活动类型「买赠」补 type-chip-5，消除 e2e
 * 靠 .chip+中文文本定位的最高危易碎点）。受控：modelValue in / update:modelValue out。
 */
interface Opt {
  value: string | number
  label: string
  testid?: string
}
defineProps<{ modelValue: string | number; options: Opt[] }>()
defineEmits<{ (e: 'update:modelValue', v: string | number): void }>()
</script>

<template>
  <div class="seg" role="group">
    <button
      v-for="o in options"
      :key="o.value"
      type="button"
      class="chip"
      :class="{ active: o.value === modelValue }"
      :data-testid="o.testid"
      @click="$emit('update:modelValue', o.value)"
    >{{ o.label }}</button>
  </div>
</template>

<style scoped>
.seg { display: inline-flex; gap: var(--sp-1); padding: 3px; background: var(--bg-soft); border: 1px solid var(--border); border-radius: var(--radius-sm); flex-wrap: wrap; }
.chip {
  padding: var(--sp-1) var(--sp-3); border: 1px solid transparent; border-radius: var(--radius-sm);
  background: transparent; color: var(--text-soft); font-size: var(--fs-sm); cursor: pointer; font-family: inherit;
}
.chip:hover { color: var(--text); }
/* PR-2：选中态改「底部下划线」。原先靠 抬升面+阴影+描边 三层表达，在换代后的纸色阶上
   与未选中差别过弱；下划线是明确的、且不随主题明暗改变可辨识度。
   刻意不做滑块位移动画——chip 组会换行，滑块在换行处必然错位。 */
.chip.active {
  background: var(--bg-elev); color: var(--accent); border-color: var(--border);
  box-shadow: inset 0 -2px 0 var(--accent);
  font-weight: var(--fw-bold);
}
@media (pointer: coarse) { .chip { min-height: var(--touch-min); } }
</style>
