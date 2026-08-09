<script setup lang="ts">
/**
 * 阶梯档位刻度尺（PR-4）——把「schema 驱动的动态表单」从 key-value 泥潭里捞出来的具体手段。
 *
 * <p><b>它替代了什么</b>：原来阶梯是 N 行 `起 / 止 / 奖励` 输入框。档位的**顺序关系、间距大小、
 * 覆盖是否连续**这三件事全靠心算——结果是重叠没人发现（两个档位争抢同一笔订单）、
 * 断档没人发现（某个金额区间一分钱优惠都没有，而运营以为配全了）。尺上一眼就能看见。
 *
 * <p><b>与设计规范的一处偏离（有意）</b>：规范里的演示是「拖第 2 个卡子到 280 → 出现重叠斜纹」，
 * 这要求编辑模型允许拖出非法状态。我改成**边界卡子**——每个卡子是相邻两档的公共边界，
 * 拖动同时改左档的上界与右档的下界，**结构上就产生不了重叠与断档**。
 * 理由：让运营先拖出错误、再靠红色提示去修，是把校验当交互用；能从源头杜绝的错误不该留给校验。
 * 校验并没有删——它仍然跑，用来兜住**从库里读进来的**历史数据（那些可能本来就有重叠或断档）。
 */
import { computed, ref } from 'vue'
import { normalizeTiers, validateTiers, type Tier } from './tierLogic'
import type { LadderRow } from '../logic'

const props = withDefaults(defineProps<{
  modelValue: LadderRow[]
  /** 尺的量程上限。默认取「末档下界 × 1.5」与 1200 的较大者 */
  max?: number
}>(), { max: 0 })

const emit = defineEmits<{ (e: 'update:modelValue', v: LadderRow[]): void }>()

const tiers = computed<Tier[]>(() => normalizeTiers(props.modelValue))
const issues = computed(() => validateTiers(tiers.value))

const span = computed(() => {
  if (props.max > 0) return props.max
  const last = tiers.value.at(-1)
  return Math.max(1200, last ? Math.ceil((last.min * 1.5) / 100) * 100 : 1200)
})

const pct = (v: number) => Math.max(0, Math.min(100, (v / span.value) * 100))

/** 边界卡子：相邻两档的公共分界。第一档下界固定为起点，不给卡子（满 0 即减没有意义的边界）。 */
const knobs = computed(() => tiers.value.slice(1).map((t, i) => ({ index: i + 1, at: t.min })))

const rail = ref<HTMLElement | null>(null)
const dragging = ref<number | null>(null)

/** 步进 20 元——与尺上的次刻度对齐，拖出来的值天然是整数。 */
const STEP = 20

function commit(index: number, value: number) {
  const sorted = tiers.value
  const lo = (sorted[index - 1]?.min ?? 0) + STEP
  const hi = sorted[index + 1] ? sorted[index + 1].min - STEP : span.value
  const v = Math.round(Math.max(lo, Math.min(hi, value)) / STEP) * STEP

  const next = sorted.map((t) => ({ ...t }))
  next[index].min = v
  next[index - 1].max = v          // 边界卡子同时改左档上界 → 结构上不产生重叠/断档
  emit('update:modelValue', next.map((t) => ({ min: t.min, max: t.max ?? '', reward: t.reward })))
}

function onPointerDown(e: PointerEvent, index: number) {
  dragging.value = index
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
}
function onPointerMove(e: PointerEvent) {
  if (dragging.value === null || !rail.value) return
  const r = rail.value.getBoundingClientRect()
  commit(dragging.value, ((e.clientX - r.left) / r.width) * span.value)
}
function onPointerUp() { dragging.value = null }

function onKey(e: KeyboardEvent, index: number) {
  const step = e.shiftKey ? STEP * 10 : STEP
  const cur = tiers.value[index].min
  if (e.key === 'ArrowRight') commit(index, cur + step)
  else if (e.key === 'ArrowLeft') commit(index, cur - step)
  else return
  e.preventDefault()
}

function setReward(index: number, raw: string) {
  const next = tiers.value.map((t) => ({ ...t }))
  next[index].reward = Number(raw) || 0
  emit('update:modelValue', next.map((t) => ({ min: t.min, max: t.max ?? '', reward: t.reward })))
}

function addTier() {
  const sorted = tiers.value
  const last = sorted.at(-1)
  const at = last ? last.min + 300 : 100
  const next = sorted.map((t) => ({ ...t }))
  if (next.length) next[next.length - 1].max = at
  next.push({ min: at, max: null, reward: last ? last.reward * 2 : 10 })
  emit('update:modelValue', next.map((t) => ({ min: t.min, max: t.max ?? '', reward: t.reward })))
}

function removeTier(index: number) {
  const next = tiers.value.filter((_, i) => i !== index)
  if (next.length) next[next.length - 1].max = null
  emit('update:modelValue', next.map((t) => ({ min: t.min, max: t.max ?? '', reward: t.reward })))
}

const axisTicks = computed(() => {
  const step = span.value / 4
  return [0, step, step * 2, step * 3, span.value].map((v) => `¥${Math.round(v).toLocaleString('zh-CN')}`)
})
</script>

<template>
  <div class="ruler-wrap" data-testid="tier-ruler">
    <div ref="rail" class="ruler" @pointermove="onPointerMove" @pointerup="onPointerUp">
      <div class="rail" />
      <!-- 各档区段：宽度即该档覆盖的金额范围 -->
      <div v-for="(t, i) in tiers" :key="i" class="seg"
           :style="{ left: pct(t.min) + '%', width: (pct(t.max ?? span) - pct(t.min)) + '%' }">
        <span class="seg-lab">满 {{ t.min }} −{{ t.reward }}</span>
      </div>
      <!-- 历史数据可能带重叠/断档：结构上拖不出来，但读进来的要标出来 -->
      <div v-for="(o, i) in issues.overlaps" :key="'o' + i" class="flaw clash"
           :style="{ left: pct(o.from) + '%', width: Math.max(1.2, pct(o.to) - pct(o.from)) + '%' }" />
      <div v-for="(g, i) in issues.gaps" :key="'g' + i" class="flaw gap"
           :style="{ left: pct(g.from) + '%', width: Math.max(1.2, pct(g.to) - pct(g.from)) + '%' }" />
      <div v-for="k in knobs" :key="k.index" class="knob" :style="{ left: pct(k.at) + '%' }"
           role="slider" tabindex="0" :aria-valuemin="0" :aria-valuemax="span" :aria-valuenow="k.at"
           :aria-label="`第 ${k.index + 1} 档门槛`" :data-testid="`tier-knob-${k.index}`"
           @pointerdown="onPointerDown($event, k.index)" @keydown="onKey($event, k.index)">
        <span class="tag">¥{{ k.at }}</span>
      </div>
      <div class="axis"><span v-for="(a, i) in axisTicks" :key="i">{{ a }}</span></div>
    </div>

    <p v-if="issues.message" class="flaw-msg" role="alert" data-testid="tier-issue">{{ issues.message }}</p>

    <div class="rewards">
      <label v-for="(t, i) in tiers" :key="i" class="rw">
        <span>满 {{ t.min }} 减</span>
        <input type="number" :value="t.reward" :data-testid="`tier-reward-${i}`"
               @input="setReward(i, ($event.target as HTMLInputElement).value)" />
        <button type="button" class="del" :aria-label="`删除第 ${i + 1} 档`" @click="removeTier(i)">×</button>
      </label>
      <button type="button" class="add" data-testid="tier-add" @click="addTier">+ 添加档位</button>
    </div>
  </div>
</template>

<style scoped>
.ruler-wrap { margin: var(--gap-group) 0; }
.ruler { position: relative; height: 74px; user-select: none; touch-action: none; }
.rail {
  position: absolute; left: 0; right: 0; top: 30px; height: 16px;
  background: var(--bg-sunken); border: var(--hairline) solid var(--border-strong); border-radius: 2px;
  background-image:
    repeating-linear-gradient(to right, var(--border-strong) 0 1px, transparent 1px 8.33%),
    repeating-linear-gradient(to right, var(--border-strong) 0 1px, transparent 1px 1.67%);
  background-size: 100% 7px, 100% 4px;
  background-position: 0 100%, 0 100%;
  background-repeat: no-repeat, no-repeat;
}
.seg { position: absolute; top: 31px; height: 14px; background: var(--accent-ink, rgba(164,37,107,.14)); }
.seg-lab {
  position: absolute; left: 4px; top: -16px; white-space: nowrap;
  font-family: var(--mono); font-size: 10px; color: var(--text-faint);
}
.flaw { position: absolute; top: 31px; height: 14px; }
.flaw.clash { background: repeating-linear-gradient(45deg, var(--err) 0 3px, transparent 3px 6px); }
.flaw.gap { background: repeating-linear-gradient(45deg, var(--text-faint) 0 3px, transparent 3px 6px); }
.knob {
  position: absolute; top: 20px; width: 16px; height: 36px; margin-left: -8px;
  border-radius: 3px; background: var(--bg-elev); border: 2px solid var(--accent);
  cursor: grab; z-index: 2;
}
.knob:active { cursor: grabbing; }
.knob::after { content: ''; position: absolute; left: 50%; top: 50%; width: 1px; height: 14px; margin: -7px 0 0 -.5px; background: var(--accent); }
.tag {
  position: absolute; left: 50%; top: -19px; transform: translateX(-50%); white-space: nowrap;
  font-family: var(--mono); font-size: 10px; color: var(--accent); font-weight: var(--fw-bold);
}
.axis {
  position: absolute; left: 0; right: 0; top: 52px; display: flex; justify-content: space-between;
  font-family: var(--mono); font-size: 10px; color: var(--text-faint);
}
.flaw-msg {
  display: flex; align-items: center; gap: 6px; margin: 4px 0 0;
  font-size: var(--fs-sm); color: var(--err); font-weight: var(--fw-bold);
}
.flaw-msg::before {
  content: ''; width: 10px; height: 10px; flex: none;
  background: repeating-linear-gradient(45deg, var(--err) 0 2px, transparent 2px 4px);
}
.rewards { display: flex; flex-wrap: wrap; gap: var(--gap-inline); margin-top: var(--gap-group); }
.rw { display: inline-flex; align-items: center; gap: 5px; font-size: var(--fs-sm); }
.rw input {
  width: 78px; min-height: 32px; padding: var(--sp-1) var(--sp-2);
  border: var(--hairline) solid var(--border); border-radius: var(--radius-sm);
  background: var(--bg-elev); color: var(--text);
}
.del { border: 0; background: none; color: var(--err); cursor: pointer; font-size: 16px; line-height: 1; padding: 0 4px; }
.add {
  border: var(--hairline) dashed var(--accent-line); background: var(--accent-soft); color: var(--accent);
  border-radius: var(--radius-sm); padding: var(--sp-1) var(--sp-3); cursor: pointer; font-size: var(--fs-sm);
}
@media (pointer: coarse) { .knob { width: 22px; margin-left: -11px; } .rw input { min-height: var(--touch-min); } }
</style>
