<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { spuDiscount, queryGifts } from '../activityApi'
import { splitNums, splitStrs, numOrNull } from '../logic'
import { errText } from '@/shared/apiClient'
import type { SpuDiscountRequest } from '@/shared/types'
import Card from '@/shared/ui/Card.vue'
import Banner from '@/shared/ui/Banner.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'
import Icon from '@/shared/ui/Icon.vue'

const form = ref({ spu: '', user: '1001', district: '', tags: '', amount: '200', qty: '1' })
const busy = ref(false)
const err = ref('')
const result = ref<Record<string, unknown> | null>(null)
const mode = ref<'discount' | 'gifts'>('discount')
let ctrl: AbortController | null = null
let requestSequence = 0

const traces = computed(() => Array.isArray(result.value?.traces) ? result.value?.traces as string[] : [])
const gifts = computed(() => Array.isArray(result.value?.gifts) ? result.value?.gifts as Record<string, unknown>[] : [])

function resetResult(): void {
  result.value = null
  err.value = ''
}

function selectMode(nextMode: 'discount' | 'gifts'): void {
  if (mode.value !== nextMode) resetResult()
  mode.value = nextMode
}

function fmtMoney(value: unknown): string {
  const number = Number(value)
  return Number.isNaN(number) ? '-' : number.toFixed(2)
}

function applyExample(): void {
  form.value = { spu: '990011,990012', user: '1001', district: '110000', tags: 'vip,new', amount: '200', qty: '1' }
  resetResult()
}

function clearForm(): void {
  form.value = { spu: '', user: '1001', district: '', tags: '', amount: '200', qty: '1' }
  resetResult()
}

async function run(kind: 'discount' | 'gifts'): Promise<void> {
  const spuIds = splitNums(form.value.spu)
  mode.value = kind
  err.value = ''
  result.value = null
  if (!spuIds.length) {
    err.value = '请至少填写一个 SPU ID'
    return
  }

  const sequence = ++requestSequence
  busy.value = true
  ctrl?.abort()
  const controller = new AbortController()
  ctrl = controller
  const body: SpuDiscountRequest = {
    spuIdList: spuIds,
    userId: numOrNull(form.value.user),
    userDistrictId: form.value.district || null,
    userTags: splitStrs(form.value.tags),
    orderAmount: numOrNull(form.value.amount),
    quantity: numOrNull(form.value.qty),
  }
  try {
    const response = kind === 'discount'
      ? await spuDiscount(body, controller.signal)
      : await queryGifts(body, controller.signal)
    if (sequence !== requestSequence) return
    if (!response.ok) {
      err.value = errText(response)
      return
    }
    result.value = response.json
  } catch (error) {
    if (sequence === requestSequence && (error as Error).name !== 'AbortError') err.value = (error as Error).message
  } finally {
    if (sequence === requestSequence) busy.value = false
  }
}

onUnmounted(() => {
  ctrl?.abort()
  requestSequence += 1
})
</script>

<template>
  <section data-testid="validate-view">
    <PageHeader
      title="优惠决策验证"
      subtitle="模拟真实订单上下文，验证已上线活动是否命中，并查看完整决策轨迹"
      :breadcrumb="[{ label: '控制台' }, { label: '优惠验证' }]"
    />

    <div class="mode-picker" aria-label="验证能力选择">
      <button type="button" :disabled="busy" :class="{ active: mode === 'discount' }" :aria-pressed="mode === 'discount'" @click="selectMode('discount')">
        <span class="mode-icon discount"><Icon name="badge-check" :size="20" /></span>
        <span><small>DISCOUNT DECISION</small><strong>红包优惠计算</strong><i>返回命中活动、优惠金额和合并策略</i></span>
        <span class="choice"><Icon v-if="mode === 'discount'" name="check" :size="14" /></span>
      </button>
      <button type="button" :disabled="busy" :class="{ active: mode === 'gifts' }" :aria-pressed="mode === 'gifts'" @click="selectMode('gifts')">
        <span class="mode-icon gifts"><Icon name="inbox" :size="20" /></span>
        <span><small>GIFT DECISION</small><strong>买赠赠品查询</strong><i>返回所有生效赠品与权益明细</i></span>
        <span class="choice"><Icon v-if="mode === 'gifts'" name="check" :size="14" /></span>
      </button>
    </div>

    <div class="validate-grid">
      <section class="context-card" @input="resetResult">
        <header class="card-head">
          <div><span class="step">01</span><div><h2>构造决策上下文</h2><p>这些字段会作为 Drools facts 参与规则匹配。</p></div></div>
          <button type="button" class="example" @click="applyExample"><Icon name="zap" :size="14" /> 填入示例</button>
        </header>

        <div class="form-section">
          <div class="section-label"><span>商品信息</span><small>必填</small></div>
          <label class="field full"><span>SPU 列表 <i>多个 ID 使用逗号分隔</i></span><div class="input-wrap"><Icon name="inbox" :size="15" /><input v-model="form.spu" placeholder="例如 990011,990012" data-testid="v-spu" /></div></label>
          <div class="field-grid">
            <label class="field"><span>订单金额</span><div class="input-wrap"><b>¥</b><input v-model="form.amount" type="number" /></div></label>
            <label class="field"><span>商品数量</span><div class="input-wrap"><Icon name="layers" :size="15" /><input v-model="form.qty" type="number" /></div></label>
          </div>
        </div>

        <div class="form-section">
          <div class="section-label"><span>用户画像</span><small>用于资格条件</small></div>
          <div class="field-grid">
            <label class="field"><span>用户 ID</span><div class="input-wrap"><Icon name="badge-check" :size="15" /><input v-model="form.user" type="number" /></div></label>
            <label class="field"><span>用户地域</span><div class="input-wrap"><Icon name="radio" :size="15" /><input v-model="form.district" placeholder="例如 110000" /></div></label>
          </div>
          <label class="field full"><span>用户标签 <i>多个标签使用逗号分隔</i></span><div class="input-wrap"><Icon name="layers" :size="15" /><input v-model="form.tags" placeholder="例如 vip,new" /></div></label>
        </div>

        <div class="actions">
          <button class="clear" type="button" :disabled="busy" @click="clearForm">清空</button>
          <button class="run" :class="{ secondary: mode !== 'discount' }" type="button" :disabled="busy" data-testid="v-discount" @click="run('discount')">
            <Icon :name="busy && mode === 'discount' ? 'refresh' : 'play'" :size="16" :class="{ spinning: busy && mode === 'discount' }" /> {{ busy && mode === 'discount' ? '正在计算…' : '运行优惠决策' }}
          </button>
          <button class="run gift-run" :class="{ secondary: mode !== 'gifts' }" type="button" :disabled="busy" data-testid="v-gifts" @click="run('gifts')">
            <Icon :name="busy && mode === 'gifts' ? 'refresh' : 'play'" :size="16" :class="{ spinning: busy && mode === 'gifts' }" /> {{ busy && mode === 'gifts' ? '正在查询…' : '查询生效赠品' }}
          </button>
        </div>
      </section>

      <section class="result-card" :aria-busy="busy">
        <header class="card-head"><div><span class="step">02</span><div><h2>决策结果</h2><p>{{ mode === 'discount' ? '红包命中与优惠计算' : '买赠权益与赠品明细' }}</p></div></div></header>

        <div v-if="busy" class="loading-result" role="status" aria-live="polite">
          <span><Icon name="workflow" :size="24" /></span><h3>规则引擎正在决策</h3><p>正在筛选有效活动、计算资格条件并应用合并策略…</p><div><i /><i /><i /></div>
        </div>
        <Banner v-else-if="err" kind="err" role="alert" class="result-error" data-testid="v-error"><strong>决策请求未完成</strong><span>{{ err }}</span></Banner>
        <template v-else-if="result">
          <div v-if="mode === 'discount'" class="decision-summary" :class="result.hit ? 'hit' : 'miss'" data-testid="validate-result">
            <span class="decision-icon"><Icon :name="result.hit ? 'badge-check' : 'x'" :size="25" /></span>
            <div><small>{{ result.hit ? 'RULE MATCHED' : 'NO RULE MATCHED' }}</small><h3>{{ result.hit ? '命中优惠活动' : '本次未命中优惠' }}</h3><p>{{ result.hit ? `${result.hitActivityName || '活动'} 已应用到当前订单` : '没有已上线活动同时满足商品与用户条件' }}</p></div>
            <strong v-if="result.hit" class="hit-amount">- ¥{{ fmtMoney(result.hitAmount) }}</strong>
          </div>
          <div v-else class="decision-summary" :class="gifts.length ? 'hit' : 'miss'" data-testid="validate-result">
            <span class="decision-icon"><Icon name="inbox" :size="25" /></span>
            <div><small>GIFT RESULT</small><h3>{{ gifts.length ? `返回 ${gifts.length} 项赠品` : '没有生效赠品' }}</h3><p>决策模式：{{ result.mode || '-' }}</p></div>
          </div>

          <div v-if="mode === 'discount' && result.hit" class="result-metrics">
            <article><small>命中活动 ID</small><strong class="mono">{{ result.hitActivityId }}</strong></article>
            <article><small>合并策略</small><strong>{{ result.strategy || '-' }}</strong></article>
            <article><small>决策模式</small><strong>{{ result.mode || '-' }}</strong></article>
          </div>

          <Card v-if="mode === 'gifts' && gifts.length" title="赠品明细">
            <div class="gift-list">
              <div v-for="(gift, index) in gifts" :key="index" class="gift-row"><span>{{ index + 1 }}</span><div><strong>{{ gift.giftName }}</strong><small>{{ gift.giftType || gift.rightType || '赠品' }}</small></div><b>×{{ gift.giftNum }} · {{ fmtMoney(gift.absoluteAmount) }}</b></div>
            </div>
          </Card>

          <div class="trace-panel">
            <div class="trace-head"><span><Icon name="workflow" :size="15" /> 决策轨迹</span><small>{{ traces.length }} STEPS</small></div>
            <div v-if="traces.length" class="traces">
              <div v-for="(trace, index) in traces" :key="index" class="trace-row"><span><i />{{ String(index + 1).padStart(2, '0') }}</span><p>{{ trace }}</p></div>
            </div>
            <div v-else class="no-trace">本次响应没有返回决策轨迹</div>
          </div>
        </template>
        <EmptyState v-else icon="scale" title="等待运行决策" hint="填写左侧商品和用户上下文，运行后会在这里展示命中结果与完整轨迹。" />
      </section>
    </div>
  </section>
</template>

<style scoped>
.mode-picker { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); margin-bottom: var(--sp-4); }.mode-picker > button { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--sp-3); min-width: 0; padding: var(--sp-3) var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); color: var(--text); cursor: pointer; text-align: left; box-shadow: var(--shadow-sm); }.mode-picker > button:hover { border-color: var(--border-strong); }.mode-picker > button:disabled { cursor: wait; opacity: .7; }.mode-picker > button.active { border-color: var(--accent); background: linear-gradient(100deg, var(--accent-soft), var(--bg-elev)); box-shadow: 0 0 0 1px var(--accent-line); }.mode-icon { display: inline-flex; align-items: center; justify-content: center; width: 42px; height: 42px; border-radius: 12px; background: var(--accent-soft); color: var(--accent); }.mode-icon.gifts { background: var(--blue-soft); color: var(--blue); }.mode-picker small, .mode-picker strong, .mode-picker i { display: block; }.mode-picker small { color: var(--accent); font-size: 8px; font-style: normal; letter-spacing: .08em; }.mode-picker strong { margin-top: 2px; font-size: var(--fs-sm); }.mode-picker i { overflow: hidden; margin-top: 2px; color: var(--text-faint); font-size: 9px; font-style: normal; text-overflow: ellipsis; white-space: nowrap; }.choice { display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; border: 1px solid var(--border-strong); border-radius: 50%; }.active .choice { border-color: var(--accent); background: var(--accent); color: #fff; }
.validate-grid { display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr); gap: var(--sp-4); align-items: stretch; }.context-card, .result-card { min-width: 0; overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); box-shadow: var(--shadow-sm); }.card-head { display: flex; align-items: center; justify-content: space-between; min-height: 72px; padding: var(--sp-3) var(--sp-4); border-bottom: 1px solid var(--border); background: var(--bg-soft); }.card-head > div { display: flex; align-items: center; gap: var(--sp-3); }.step { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; border-radius: 9px; background: var(--accent-soft); color: var(--accent); font-size: 10px; font-weight: var(--fw-bold); font-variant-numeric: tabular-nums; }.card-head h2 { margin: 0; font-size: var(--fs-md); }.card-head p { margin: 1px 0 0; color: var(--text-faint); font-size: 9px; }.example { display: inline-flex; align-items: center; gap: var(--sp-1); padding: var(--sp-2); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--accent); cursor: pointer; font: inherit; font-size: 10px; }
.form-section { padding: var(--sp-4); border-bottom: 1px solid var(--border); }.section-label { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--sp-3); }.section-label span { font-size: 10px; font-weight: var(--fw-bold); letter-spacing: .06em; text-transform: uppercase; }.section-label small { color: var(--text-faint); font-size: 9px; }.field-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); }.field { display: flex; flex-direction: column; gap: var(--sp-1); min-width: 0; }.field.full + .field-grid, .field-grid + .field.full { margin-top: var(--sp-3); }.field > span { display: flex; justify-content: space-between; color: var(--text-soft); font-size: 10px; font-weight: var(--fw-medium); }.field > span i { color: var(--text-faint); font-size: 8px; font-style: normal; font-weight: var(--fw-medium); }.input-wrap { display: flex; align-items: center; gap: var(--sp-2); min-height: 40px; padding: 0 var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-soft); color: var(--text-faint); }.input-wrap:focus-within { border-color: var(--accent); background: var(--bg-elev); box-shadow: var(--focus-ring); }.input-wrap input { width: 100%; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--text); font: inherit; font-size: var(--fs-sm); }.input-wrap b { color: var(--text-soft); font-size: var(--fs-sm); }.actions { display: flex; gap: var(--sp-2); justify-content: flex-end; padding: var(--sp-4); }.actions button { min-height: 42px; border-radius: var(--radius-sm); cursor: pointer; font: inherit; font-size: var(--fs-xs); }.actions button:disabled { cursor: wait; opacity: .6; }.clear { padding: 0 var(--sp-4); border: 1px solid var(--border); background: var(--bg-elev); color: var(--text-soft); }.run { display: inline-flex; align-items: center; justify-content: center; gap: var(--sp-2); min-width: 170px; padding: 0 var(--sp-4); border: 0; background: linear-gradient(100deg, var(--accent), var(--accent-2)); color: #fff; font-weight: var(--fw-semibold); box-shadow: 0 8px 18px color-mix(in srgb, var(--accent) 20%, transparent); }.gift-run { background: linear-gradient(100deg, var(--blue), #0891b2); }.run.secondary { border: 1px solid var(--border); background: var(--bg-elev); color: var(--text-soft); box-shadow: none; }.spinning { animation: spin .9s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
.loading-result { display: flex; min-height: 470px; flex-direction: column; align-items: center; justify-content: center; padding: var(--sp-6); text-align: center; }.loading-result > span { display: inline-flex; align-items: center; justify-content: center; width: 58px; height: 58px; border-radius: 18px; background: var(--accent-soft); color: var(--accent); animation: pulse 1s ease-in-out infinite alternate; }@keyframes pulse { to { transform: scale(1.06); box-shadow: 0 0 0 10px color-mix(in srgb, var(--accent) 8%, transparent); } }.loading-result h3 { margin: var(--sp-3) 0 var(--sp-1); font-size: var(--fs-md); }.loading-result p { max-width: 360px; margin: 0; color: var(--text-faint); font-size: 10px; }.loading-result > div { width: min(280px, 80%); margin-top: var(--sp-5); }.loading-result i { display: block; height: 7px; margin-top: var(--sp-2); border-radius: var(--radius-pill); background: var(--bg-soft); }.loading-result i:nth-child(2) { width: 80%; }.loading-result i:nth-child(3) { width: 60%; }.result-error { display: flex; min-height: 110px; flex-direction: column; justify-content: center; margin: var(--sp-4); }.result-error span { margin-top: 2px; font-size: 10px; }
.decision-summary { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--sp-3); margin: var(--sp-4); padding: var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg); }.decision-summary.hit { border-color: color-mix(in srgb, var(--green) 28%, var(--border)); background: var(--green-soft); }.decision-summary.miss { background: var(--bg-soft); }.decision-icon { display: inline-flex; align-items: center; justify-content: center; width: 46px; height: 46px; border-radius: 14px; background: var(--bg-elev); color: var(--green); }.miss .decision-icon { color: var(--text-faint); }.decision-summary small { color: var(--green); font-size: 8px; letter-spacing: .08em; }.decision-summary h3 { margin: 2px 0; font-size: var(--fs-md); }.decision-summary p { margin: 0; color: var(--text-soft); font-size: 9px; }.hit-amount { color: var(--green); font-size: 20px; font-variant-numeric: tabular-nums; }.result-metrics { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--sp-2); margin: 0 var(--sp-4) var(--sp-4); }.result-metrics article { min-width: 0; padding: var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-soft); }.result-metrics small, .result-metrics strong { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.result-metrics small { color: var(--text-faint); font-size: 8px; }.result-metrics strong { margin-top: 2px; font-size: 10px; }.mono { font-family: var(--mono); }
.gift-list { display: flex; flex-direction: column; }.gift-row { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: var(--sp-2); padding: var(--sp-2) 0; border-bottom: 1px solid var(--border); }.gift-row:last-child { border-bottom: 0; }.gift-row > span { display: inline-flex; align-items: center; justify-content: center; width: 26px; height: 26px; border-radius: 7px; background: var(--bg-soft); color: var(--text-faint); font-size: 9px; font-variant-numeric: tabular-nums; }.gift-row strong, .gift-row small { display: block; }.gift-row strong { font-size: 10px; }.gift-row small { color: var(--text-faint); font-size: 8px; }.gift-row b { color: var(--text-soft); font-size: 9px; font-variant-numeric: tabular-nums; }
.trace-panel { overflow: hidden; margin: var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-sm); }.trace-head { display: flex; align-items: center; justify-content: space-between; padding: var(--sp-2) var(--sp-3); border-bottom: 1px solid var(--border); background: var(--bg-soft); }.trace-head span { display: inline-flex; align-items: center; gap: var(--sp-2); font-size: 10px; font-weight: var(--fw-semibold); }.trace-head small { color: var(--text-faint); font-size: 8px; font-variant-numeric: tabular-nums; }.traces { padding: var(--sp-2) var(--sp-3); }.trace-row { display: grid; grid-template-columns: 34px 1fr; gap: var(--sp-2); min-height: 36px; }.trace-row > span { position: relative; color: var(--accent); font-size: 9px; font-variant-numeric: tabular-nums; }.trace-row > span::after { content: ''; position: absolute; top: 15px; bottom: -4px; left: 3px; width: 1px; background: var(--border); }.trace-row:last-child > span::after { display: none; }.trace-row > span i { display: inline-block; width: 7px; height: 7px; margin-right: 5px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }.trace-row p { margin: 0; color: var(--text-soft); font-size: 9px; line-height: 1.55; }.no-trace { padding: var(--sp-4); color: var(--text-faint); font-size: 10px; text-align: center; }
@media (max-width: 1100px) { .validate-grid { grid-template-columns: 1fr; }.loading-result { min-height: 300px; } }
@media (max-width: 700px) { .mode-picker { grid-template-columns: 1fr; }.field-grid { grid-template-columns: 1fr; }.actions { align-items: stretch; flex-direction: column; }.actions button { width: 100%; }.decision-summary { grid-template-columns: auto minmax(0, 1fr); }.hit-amount { grid-column: 2; }.result-metrics { grid-template-columns: 1fr; } }
</style>
