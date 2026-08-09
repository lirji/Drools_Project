<script setup lang="ts">
/**
 * 行内 sparkline（PR-3）。无坐标轴、无网格、无点——它只回答「趋势往哪走」。
 *
 * `preserveAspectRatio="none"` 会横向压扁图形，所以线宽必须靠 `vector-effect="non-scaling-stroke"`
 * 保住；`stroke` 走 CSS 类而不是展示属性——展示属性里的 `var()` 在 WebKit 会被判非法整体丢弃
 * （设计评审点名的 X14）。
 *
 * 视觉换代 0809：加发光折线 + 渐变面积（Grafana 的做法）。**几何与既有断言一个字不动**：
 * - 折线 path 必须仍是 DOM 里的**第一个** path —— `viz.test.ts` 用 `find('path')`（取第一个）
 *   断言 `class=empty` 与 `d="M0,15L100,15"`，面积 path 若插在它之前会直接把两条测试打红。
 * - 面积只在有真实数据时画（`empty` 时画面积等于给"没有数据"上色，是另一种假图）。
 * - 发光用 `filter: drop-shadow`：`box-shadow` 走盒模型，画不出折线的形状。
 */
import { computed } from 'vue'
import { sparklinePath } from './vizMath'

const props = withDefaults(defineProps<{ values: number[]; label?: string }>(), { label: '' })
const d = computed(() => sparklinePath(props.values))
const empty = computed(() => props.values.length < 2)
/** 面积 = 折线 + 沿底边闭合。复用同一条 path 数据，不引入第二套几何。 */
const areaD = computed(() => (empty.value ? '' : `${d.value}L100,30L0,30Z`))
/** 同页多个 sparkline 时渐变 id 不能撞车；用 values 的指纹而不是随机数，保证 SSR/快照稳定。 */
const gradId = computed(() => `spark-grad-${props.values.length}-${Math.abs(props.values.reduce((a, b) => a + b, 0) | 0)}`)
</script>

<template>
  <svg class="spark" viewBox="0 0 100 30" preserveAspectRatio="none"
       role="img" :aria-label="label || `趋势，共 ${values.length} 个采样点`">
    <path :d="d" :class="empty ? 'line empty' : 'line'" vector-effect="non-scaling-stroke" />
    <defs v-if="!empty">
      <linearGradient :id="gradId" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="var(--dv-1)" stop-opacity=".34" />
        <stop offset="100%" stop-color="var(--dv-1)" stop-opacity="0" />
      </linearGradient>
    </defs>
    <path v-if="!empty" class="area" :d="areaD" :fill="`url(#${gradId})`" />
  </svg>
</template>

<style scoped>
.spark { width: 42px; height: 14px; display: block; overflow: visible; }
.line {
  fill: none; stroke: var(--dv-1); stroke-width: 2;
  filter: drop-shadow(0 0 4px color-mix(in srgb, var(--dv-1) 55%, transparent));
}
/* 零数据画虚线基线——空白读起来像"没渲染出来"，虚线读起来像"确实是 0"。
   基线不发光：它表达的是"没有数据"，发光会让它读成一条有效的趋势线。 */
.line.empty { stroke: var(--dv-5); stroke-dasharray: 3 3; filter: none; }
.area { stroke: none; }
</style>
