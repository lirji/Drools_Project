<script setup lang="ts">
/**
 * 发放额度量筒（PR-3）。带管壁 + 每 10% 一格刻度环 + 液面 + **临界虚线**。
 *
 * 预算烧穿是运营最贵的事故。液面高度与临界线的相对位置是**前注意力**就能处理的图形关系——
 * 在一屏 20 行里它会最先跳出来，比任何数字都快。越线时颜色与**文字**同时改变（双编码）。
 */
import { computed } from 'vue'
import { gaugeState } from './vizMath'

const props = withDefaults(defineProps<{ percent: number | null; threshold?: number }>(), { threshold: 80 })
const st = computed(() => gaugeState(props.percent ?? 0, props.threshold))
</script>

<template>
  <div v-if="percent === null" class="na">未启用</div>
  <div v-else>
    <div class="tube" :class="{ over: st.over }" role="img"
         :aria-label="`额度已用 ${st.label}${st.over ? '，已越过临界线' : ''}`">
      <i class="fill" :style="{ width: Math.min(100, Math.max(0, percent)) + '%' }" />
      <span class="ticks" />
      <span class="crit" :style="{ left: threshold + '%' }" />
    </div>
    <span class="lab" :class="{ over: st.over }">{{ st.label }}</span>
  </div>
</template>

<style scoped>
.na { font-family: var(--mono); font-size: 10px; color: var(--text-faint); }
.tube {
  position: relative; height: 10px; border: var(--hairline) solid var(--border-strong);
  border-radius: 2px; background: var(--bg-sunken); overflow: hidden;
}
.fill { position: absolute; left: 0; top: 0; bottom: 0; background: var(--ramp-4); }
.tube.over .fill { background: var(--err); }
.ticks {
  position: absolute; inset: 0; opacity: .55;
  background: repeating-linear-gradient(to right, transparent 0 calc(10% - 1px), var(--bg-elev) calc(10% - 1px) 10%);
}
.crit { position: absolute; top: -1px; bottom: -1px; border-left: 2px dashed var(--rule); }
.lab { display: block; margin-top: 3px; font-family: var(--mono); font-size: 10px; color: var(--text-faint); }
.lab.over { color: var(--err); font-weight: var(--fw-bold); }
</style>
