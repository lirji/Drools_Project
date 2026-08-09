<script setup lang="ts">
/**
 * 生效时间窗甘特条（PR-3）。**所有行共享同一根轴**——这是它全部价值的来源：
 * 不读日期就知道「还剩几天 / 几天后开跑 / 已经跑完」，更重要的是能**跨行**看出
 * 「这三个活动窗口叠在一起了」，而那正是运营最怕的活动打架。
 *
 * 轴映射交给 {@link mapWindow}（已单测钉死）。列宽必须由调用方定死，
 * 弹性伸缩会让第 1 行的 50% 与第 5 行的 50% 不在同一横坐标，图就在说谎。
 */
import { computed } from 'vue'
import { mapWindow } from './vizMath'

const props = withDefaults(defineProps<{
  start: string | number | Date
  end: string | number | Date
  now?: string | number | Date
  /** 已下线：不画条，只留轴与游标 */
  muted?: boolean
  state?: 'live' | 'warmup' | 'ended'
}>(), { state: 'live', muted: false })

const g = computed(() => mapWindow(new Date(props.start), new Date(props.end), new Date(props.now ?? Date.now())))
const label = computed(() => {
  const s = new Date(props.start), e = new Date(props.end)
  const f = (d: Date) => `${d.getMonth() + 1}-${String(d.getDate()).padStart(2, '0')}`
  return `${f(s)} → ${f(e)}`
})
</script>

<template>
  <div class="wb" role="img" :aria-label="`生效窗 ${label}`">
    <span class="track" />
    <span v-if="!muted && !g.offAxis" class="bar" :class="state"
          :style="{ left: g.left + '%', width: g.width + '%' }" />
    <span class="now" :style="{ left: g.nowPct + '%' }" />
    <span v-if="g.cutLeft" class="cut l" aria-hidden="true" />
    <span v-if="g.cutRight" class="cut r" aria-hidden="true" />
    <span class="lbl">{{ label }}</span>
  </div>
</template>

<style scoped>
.wb { position: relative; height: 22px; min-width: 178px; }
.track {
  position: absolute; left: 0; right: 0; top: 7px; height: 8px; border-radius: 2px;
  background: var(--bg-sunken);
  background-image: repeating-linear-gradient(to right, var(--border-strong) 0 1px, transparent 1px 14px);
}
.bar { position: absolute; top: 7px; height: 8px; border-radius: 2px; min-width: 6px; }
.bar.live { background: var(--ramp-5); }
.bar.warmup { background: repeating-linear-gradient(45deg, var(--blue) 0 3px, transparent 3px 6px), var(--blue-soft); }
.bar.ended { background: repeating-linear-gradient(45deg, var(--text-faint) 0 2px, transparent 2px 5px); opacity: .7; }
.now { position: absolute; top: 2px; bottom: 2px; width: 2px; background: var(--rule); }
.now::after { content: ''; position: absolute; top: -1px; left: 0; width: 6px; height: 3px; background: var(--rule); }
.cut { position: absolute; top: 8px; width: 0; height: 0; border-top: 3px solid transparent; border-bottom: 3px solid transparent; }
.cut.l { left: 0; border-right: 6px solid var(--text-faint); }
.cut.r { right: 0; border-left: 6px solid var(--text-faint); }
.lbl { position: absolute; left: 0; top: -1px; font-family: var(--mono); font-size: 9px; color: var(--text-faint); }
</style>
