<script setup lang="ts">
/**
 * 行内 sparkline（PR-3）。无坐标轴、无网格、无点——它只回答「趋势往哪走」。
 *
 * `preserveAspectRatio="none"` 会横向压扁图形，所以线宽必须靠 `vector-effect="non-scaling-stroke"`
 * 保住；`stroke` 走 CSS 类而不是展示属性——展示属性里的 `var()` 在 WebKit 会被判非法整体丢弃
 * （设计评审点名的 X14）。
 */
import { computed } from 'vue'
import { sparklinePath } from './vizMath'

const props = withDefaults(defineProps<{ values: number[]; label?: string }>(), { label: '' })
const d = computed(() => sparklinePath(props.values))
const empty = computed(() => props.values.length < 2)
</script>

<template>
  <svg class="spark" viewBox="0 0 100 30" preserveAspectRatio="none"
       role="img" :aria-label="label || `趋势，共 ${values.length} 个采样点`">
    <path :d="d" :class="empty ? 'line empty' : 'line'" vector-effect="non-scaling-stroke" />
  </svg>
</template>

<style scoped>
.spark { width: 42px; height: 14px; display: block; }
.line { fill: none; stroke: var(--dv-1); stroke-width: 2; }
/* 零数据画虚线基线——空白读起来像"没渲染出来"，虚线读起来像"确实是 0" */
.line.empty { stroke: var(--dv-5); stroke-dasharray: 3 3; }
</style>
