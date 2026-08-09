<script setup lang="ts">
/**
 * 数字块原语（视觉换代 0809 · 步骤 6）—— hero 里的统计格与页面里的 KPI 共用。
 *
 * **只用于有真实数据源的数字**。本项目已把「不画假图」写成产品立场
 * （ListView 的 metrics-notice 明说决策量/命中率/P99 没有聚合接口、因此不画），
 * 这个组件不该被用来给不存在的指标做门面。
 *
 * 数字一律 mono + tabular-nums：列表滚动/数值刷新时不跳位——这一条比任何辉光都更像"专业工具"。
 * `on-deep` 变体给压在 hero 那种永远深色的面上时用（--text 在浅色档是近黑，会看不见）。
 */
withDefaults(defineProps<{
  label: string
  value: number | string
  unit?: string
  /** 压在永远深色的面（hero）上时置 true，文字改走 --on-deep* */
  onDeep?: boolean
  /** 「在跑 / 生效中」这类有信息量的状态才给脉冲点，不要当装饰 */
  live?: boolean
}>(), { onDeep: false, live: false })
</script>

<template>
  <div class="stat" :class="{ deep: onDeep }">
    <span class="stat-label">
      <i v-if="live" class="stat-dot" aria-hidden="true" />
      {{ label }}
    </span>
    <strong class="stat-value">
      {{ value }}<em v-if="unit">{{ unit }}</em>
    </strong>
  </div>
</template>

<style scoped>
.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 104px;
  padding: var(--sp-3) var(--sp-4);
  background: var(--bg-elev);
  text-align: center;
}
.stat.deep { background: color-mix(in srgb, var(--surface-deep) 62%, transparent); }
.stat-label {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  color: var(--text-faint);
  font-size: var(--fs-xs);
  white-space: nowrap;
}
.stat.deep .stat-label { color: var(--on-deep-faint); }
.stat-dot {
  width: 6px;
  height: 6px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--ok);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--ok) 22%, transparent);
}
.stat-value {
  font-family: var(--mono);
  font-variant-numeric: tabular-nums;
  font-size: var(--fs-2xl);
  font-weight: var(--fw-bold);
  letter-spacing: -.02em;
  line-height: var(--lh-tight);
  color: var(--text);
}
.stat.deep .stat-value {
  /* 用 --on-deep-accent 而不是 --accent-2：后者在浅色档是压暗版 #0C6B85，
     落在永远深色的 hero 面上只有 2.9:1，不达标。 */
  color: var(--on-deep-accent);
  /* 数值可以发光，正文绝不。半径 ≤24px、一屏 ≤3 处。 */
  text-shadow: 0 0 18px color-mix(in srgb, var(--on-deep-accent) 45%, transparent);
}
.stat-value em {
  margin-left: 3px;
  font-style: normal;
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  color: var(--text-faint);
}
.stat.deep .stat-value em { color: var(--on-deep-faint); }
</style>
