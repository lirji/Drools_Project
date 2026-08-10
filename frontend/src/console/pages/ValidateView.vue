<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { queryAddOnOptions, queryGifts, quoteAddOn, spuDiscount } from '../activityApi'
import { splitStrs } from '../logic'
import { PLAYBOOKS, isReady, type PlaybookPreset } from '../playbooks'
import { errText } from '@/shared/apiClient'
import type {
  AddOnOption,
  AddOnOptionsResponse,
  AddOnQuoteResponse,
  DecisionOrderLine,
  DiscountDecisionResponse,
  GiftDecisionResponse,
  SpuDiscountRequest,
} from '@/shared/types'
import Card from '@/shared/ui/Card.vue'
import Banner from '@/shared/ui/Banner.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'
import Icon from '@/shared/ui/Icon.vue'
import Skeleton from '@/shared/ui/Skeleton.vue'

type ValidationMode = 'discount' | 'gifts' | 'addon'
type BusyAction = 'decision' | 'quote' | null

interface ValidationScenario {
  id: string
  name: string
  plain: string
  mode: ValidationMode
  redMode: PlaybookPreset['redMode']
}

interface ContextForm {
  spu: string
  user: string | number
  district: string
  tags: string
  amount: string | number
  qty: string | number
  store: string | number
}

interface EditableOrderLine {
  id: number
  spuId: string | number
  unitPrice: string | number
  quantity: string | number
}

interface ParsedLines {
  lines: DecisionOrderLine[]
  spuIdList: number[]
  orderAmount: number
  quantity: number
}

function validationModeOf(activityType: number): ValidationMode {
  if (activityType === 5) return 'gifts'
  if (activityType === 6) return 'addon'
  return 'discount'
}

/**
 * 玩法目录是场景的唯一来源；随机金额当前没有独立模板卡，因此只额外补这一种权益形态。
 * 场景只决定调用通道和需要构造的上下文，不携带“必须命中某活动”的断言。
 *
 * 派生判据用 isReady()（preset 存在 **且** 类型在写平面白名单里），不是裸 preset——
 * 只判 preset 正是当年 addon 卡「目录跑到能力前面」的漂移路径：卡片带着 preset 却建不出来，
 * 验证页照样为它生成一个永远打不通的场景。
 */
const SCENARIOS: ValidationScenario[] = [
  ...PLAYBOOKS.flatMap((playbook): ValidationScenario[] => isReady(playbook) && playbook.preset ? [{
    id: playbook.id,
    name: playbook.name,
    plain: playbook.plain,
    mode: validationModeOf(playbook.preset.activityType),
    redMode: playbook.preset.redMode,
  }] : []),
  {
    id: 'random',
    name: '随机红包（形态验证）',
    plain: '构造随机红包决策上下文；实际区间来自已上线活动，本场景不会指定或强制命中某一张券。',
    mode: 'discount',
    redMode: 'random',
  },
]

const EMPTY_FORM = (): ContextForm => ({
  spu: '', user: '1001', district: '', tags: '', amount: '200', qty: '1', store: '',
})

let lineSequence = 0
function blankLine(): EditableOrderLine {
  lineSequence += 1
  return { id: lineSequence, spuId: '', unitPrice: '', quantity: '' }
}

const form = ref<ContextForm>(EMPTY_FORM())
const lines = ref<EditableOrderLine[]>([blankLine()])
const scenarioId = ref('flat')
const busyAction = ref<BusyAction>(null)
const err = ref('')
const quoteConflict = ref('')
const discountResult = ref<DiscountDecisionResponse | null>(null)
const giftResult = ref<GiftDecisionResponse | null>(null)
const addOnResult = ref<AddOnOptionsResponse | null>(null)
const addOnQuoteResult = ref<AddOnQuoteResponse | null>(null)
const addOnQuoteTraces = ref<string[]>([])
const selectedOptionIndex = ref<number | null>(null)
const lastRequest = ref<SpuDiscountRequest | null>(null)
let ctrl: AbortController | null = null
let requestSequence = 0

const activeScenario = computed(() => SCENARIOS.find((scenario) => scenario.id === scenarioId.value) ?? SCENARIOS[0])
const mode = computed<ValidationMode>(() => activeScenario.value.mode)
const detailMode = computed(() => activeScenario.value.redMode === 'nth')
const busy = computed(() => busyAction.value !== null)
const addOnOptions = computed<AddOnOption[]>(() => addOnResult.value?.options ?? [])
const selectedAddOnOption = computed<AddOnOption | null>(() => {
  if (selectedOptionIndex.value === null) return null
  return addOnOptions.value[selectedOptionIndex.value] ?? null
})
const hasResult = computed(() => mode.value === 'discount'
  ? discountResult.value !== null
  : mode.value === 'gifts'
    ? giftResult.value !== null
    : addOnResult.value !== null)
const traces = computed(() => mode.value === 'discount'
  ? discountResult.value?.traces ?? []
  : mode.value === 'gifts'
    ? giftResult.value?.traces ?? []
    : addOnQuoteTraces.value.length
      ? addOnQuoteTraces.value
      : addOnResult.value?.traces ?? [])

const parsedLineSummary = computed<ParsedLines | null>(() => parseOrderLines().value)
const priceBreakdown = computed(() => {
  if (activeScenario.value.redMode !== 'price' || !discountResult.value?.hit) return null
  const original = lastRequest.value?.orderAmount
  const discount = Number(discountResult.value.hitAmount)
  if (original === null || original === undefined || !Number.isFinite(original) || !Number.isFinite(discount)) return null
  return { original, discount, payable: Math.max(0, original - discount) }
})

function cancelActiveRequest(): void {
  ctrl?.abort()
  ctrl = null
  requestSequence += 1
  busyAction.value = null
}

function clearOutcomes(abort = true): void {
  if (abort) cancelActiveRequest()
  err.value = ''
  quoteConflict.value = ''
  discountResult.value = null
  giftResult.value = null
  addOnResult.value = null
  addOnQuoteResult.value = null
  addOnQuoteTraces.value = []
  selectedOptionIndex.value = null
  lastRequest.value = null
}

function onContextInput(): void {
  clearOutcomes()
}

function selectScenario(): void {
  clearOutcomes()
  if (detailMode.value && lines.value.length === 0) lines.value.push(blankLine())
}

function selectMode(nextMode: ValidationMode): void {
  const first = SCENARIOS.find((scenario) => scenario.mode === nextMode)
  if (first) scenarioId.value = first.id
  clearOutcomes()
}

function fmtMoney(value: unknown): string {
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(2) : '-'
}

function applyExample(): void {
  clearOutcomes()
  const next = EMPTY_FORM()
  next.spu = '990011'
  next.district = activeScenario.value.id === 'region' ? '310000' : '110000'
  next.tags = activeScenario.value.id === 'tagged' ? '高价值' : 'vip,new'
  next.store = activeScenario.value.id === 'store' ? '1' : ''
  if (activeScenario.value.id === 'threshold') next.amount = '200'
  if (activeScenario.value.id === 'ladder') next.amount = '600'
  if (activeScenario.value.id === 'quantity') next.qty = '2'
  if (activeScenario.value.id === 'gift') next.amount = '500'
  if (activeScenario.value.id === 'flash') next.amount = '100'
  form.value = next
  lines.value = detailMode.value
    ? [{ id: ++lineSequence, spuId: '990011', unitPrice: '100', quantity: '2' }]
    : [blankLine()]
}

function clearForm(): void {
  clearOutcomes()
  form.value = EMPTY_FORM()
  lines.value = [blankLine()]
}

function addLine(): void {
  lines.value.push(blankLine())
  clearOutcomes()
}

function removeLine(index: number): void {
  lines.value.splice(index, 1)
  clearOutcomes()
}

function selectAddOnOption(): void {
  quoteConflict.value = ''
  addOnQuoteResult.value = null
  addOnQuoteTraces.value = []
}

function positiveNumber(raw: unknown, label: string, integer = false): { value: number | null; error: string | null } {
  const text = String(raw ?? '').trim()
  if (!text) return { value: null, error: `${label}必填` }
  const value = Number(text)
  if (!Number.isFinite(value) || value <= 0) return { value: null, error: `${label}必须是有限正数` }
  if (integer && !Number.isSafeInteger(value)) return { value: null, error: `${label}必须是正整数（且在安全范围内）` }
  return { value, error: null }
}

function optionalPositiveInteger(raw: unknown, label: string): { value: number | null; error: string | null } {
  if (!String(raw ?? '').trim()) return { value: null, error: null }
  return positiveNumber(raw, label, true)
}

function parseOrderLines(): { value: ParsedLines | null; error: string | null } {
  if (!lines.value.length) return { value: null, error: '请至少添加一条订单行' }
  const parsed: DecisionOrderLine[] = []
  let orderAmount = 0
  let quantity = 0
  for (let index = 0; index < lines.value.length; index += 1) {
    const row = lines.value[index]
    const no = index + 1
    const spu = positiveNumber(row.spuId, `第 ${no} 行 SPU ID`, true)
    const price = positiveNumber(row.unitPrice, `第 ${no} 行单价`)
    const qty = positiveNumber(row.quantity, `第 ${no} 行数量`, true)
    const firstError = spu.error ?? price.error ?? qty.error
    if (firstError) return { value: null, error: firstError }
    const line = { spuId: spu.value!, unitPrice: price.value!, quantity: qty.value! }
    parsed.push(line)
    orderAmount += line.unitPrice * line.quantity
    quantity += line.quantity
  }
  if (!Number.isFinite(orderAmount) || orderAmount <= 0 || !Number.isSafeInteger(quantity)) {
    return { value: null, error: '订单行汇总超出可计算范围' }
  }
  return {
    value: {
      lines: parsed,
      spuIdList: [...new Set(parsed.map((line) => line.spuId))],
      orderAmount: Number(orderAmount.toFixed(8)),
      quantity,
    },
    error: null,
  }
}

function parseSpuIds(raw: string): { value: number[] | null; error: string | null } {
  if (!raw.trim()) return { value: null, error: '请至少填写一个 SPU ID' }
  const parts = raw.split(',').map((part) => part.trim())
  if (parts.some((part) => !part)) return { value: null, error: 'SPU 列表存在残缺项' }
  const ids = parts.map(Number)
  if (ids.some((id) => !Number.isFinite(id) || id <= 0 || !Number.isSafeInteger(id))) {
    return { value: null, error: 'SPU ID 必须是安全范围内的有限正整数' }
  }
  return { value: [...new Set(ids)], error: null }
}

function buildRequest(): { body: SpuDiscountRequest | null; error: string | null } {
  const user = optionalPositiveInteger(form.value.user, '用户 ID')
  if (user.error) return { body: null, error: user.error }
  const store = optionalPositiveInteger(form.value.store, '店铺 ID')
  if (store.error) return { body: null, error: store.error }

  let spuIdList: number[]
  let orderAmount: number
  let quantity: number
  let requestLines: DecisionOrderLine[] | null
  if (detailMode.value) {
    const detailed = parseOrderLines()
    if (detailed.error || !detailed.value) return { body: null, error: detailed.error }
    spuIdList = detailed.value.spuIdList
    orderAmount = detailed.value.orderAmount
    quantity = detailed.value.quantity
    requestLines = detailed.value.lines
  } else {
    const spus = parseSpuIds(form.value.spu)
    if (spus.error || !spus.value) return { body: null, error: spus.error }
    const amount = positiveNumber(form.value.amount, '订单金额')
    if (amount.error || amount.value === null) return { body: null, error: amount.error }
    const qty = positiveNumber(form.value.qty, '商品数量', true)
    if (qty.error || qty.value === null) return { body: null, error: qty.error }
    spuIdList = spus.value
    orderAmount = amount.value
    quantity = qty.value
    requestLines = null
  }

  return {
    body: {
      spuIdList,
      userId: user.value,
      userDistrictId: form.value.district.trim() || null,
      userTags: splitStrs(form.value.tags),
      orderAmount,
      quantity,
      storeId: store.value,
      lines: requestLines,
    },
    error: null,
  }
}

function beginRequest(action: Exclude<BusyAction, null>): { sequence: number; controller: AbortController } {
  cancelActiveRequest()
  const controller = new AbortController()
  ctrl = controller
  const sequence = ++requestSequence
  busyAction.value = action
  return { sequence, controller }
}

function finishRequest(sequence: number): void {
  if (sequence !== requestSequence) return
  busyAction.value = null
  ctrl = null
}

async function runDecision(): Promise<void> {
  clearOutcomes()
  const built = buildRequest()
  if (!built.body) {
    err.value = built.error ?? '决策上下文不完整'
    return
  }
  const body = built.body
  const currentMode = mode.value
  const { sequence, controller } = beginRequest('decision')
  try {
    if (currentMode === 'discount') {
      const response = await spuDiscount(body, controller.signal)
      if (sequence !== requestSequence) return
      if (!response.ok || !response.json) {
        err.value = response.ok ? '优惠决策响应为空' : errText(response)
        return
      }
      discountResult.value = response.json
    } else if (currentMode === 'gifts') {
      const response = await queryGifts(body, controller.signal)
      if (sequence !== requestSequence) return
      if (!response.ok || !response.json) {
        err.value = response.ok ? '赠品决策响应为空' : errText(response)
        return
      }
      giftResult.value = response.json
    } else {
      const response = await queryAddOnOptions(body, controller.signal)
      if (sequence !== requestSequence) return
      if (!response.ok || !response.json) {
        err.value = response.ok ? '加价购选项响应为空' : errText(response)
        return
      }
      addOnResult.value = response.json
    }
    lastRequest.value = body
  } catch (error) {
    if (sequence === requestSequence && (error as Error).name !== 'AbortError') {
      err.value = (error as Error).message
    }
  } finally {
    finishRequest(sequence)
  }
}

async function requestQuote(): Promise<void> {
  const option = selectedAddOnOption.value
  if (!option) {
    err.value = '请先选择一个换购选项'
    return
  }
  const built = buildRequest()
  if (!built.body) {
    err.value = built.error ?? '决策上下文不完整'
    return
  }
  err.value = ''
  quoteConflict.value = ''
  addOnQuoteResult.value = null
  addOnQuoteTraces.value = []
  const { sequence, controller } = beginRequest('quote')
  try {
    const response = await quoteAddOn(built.body, option.activityId, option.itemName, controller.signal)
    if (sequence !== requestSequence) return
    addOnQuoteTraces.value = response.json?.traces ?? []
    if (response.status === 409) {
      quoteConflict.value = response.json?.reason || errText(response)
      return
    }
    if (!response.ok || !response.json) {
      err.value = response.ok ? '加价购报价响应为空' : errText(response)
      return
    }
    if (!response.json.ok) {
      quoteConflict.value = response.json.reason || '选项已失效或不适用于当前订单'
      return
    }
    addOnQuoteResult.value = response.json
    lastRequest.value = built.body
  } catch (error) {
    if (sequence === requestSequence && (error as Error).name !== 'AbortError') {
      err.value = (error as Error).message
    }
  } finally {
    finishRequest(sequence)
  }
}

onUnmounted(cancelActiveRequest)
</script>

<template>
  <section data-testid="validate-view">
    <PageHeader
      title="优惠决策验证"
      subtitle="用一份订单上下文验证红包、买赠与加价购；场景不会绕过真实决策或强制命中"
      :breadcrumb="[{ label: '控制台' }, { label: '优惠验证' }]"
    />

    <section class="scenario-panel" aria-labelledby="scenario-title">
      <div>
        <label id="scenario-title" for="validation-scenario">玩法场景</label>
        <select id="validation-scenario" v-model="scenarioId" data-testid="v-scenario" @change="selectScenario">
          <option v-for="scenario in SCENARIOS" :key="scenario.id" :value="scenario.id">{{ scenario.name }}</option>
        </select>
      </div>
      <p>{{ activeScenario.plain }}</p>
      <Banner kind="info" data-testid="v-scenario-note">
        场景只准备输入项和调用通道，不指定活动、也不保证命中；结果仍由当前租户全部已上线候选共同决策。
      </Banner>
    </section>

    <div class="mode-picker" aria-label="验证能力选择">
      <button type="button" data-testid="validate-mode-discount" :class="{ active: mode === 'discount' }" :aria-pressed="mode === 'discount'" @click="selectMode('discount')">
        <span class="mode-icon"><Icon name="badge-check" :size="20" /></span>
        <span><small>DISCOUNT</small><strong>红包优惠</strong><i>固定、随机、阶梯、折扣、一口价与第 N 件折</i></span>
        <span class="choice"><Icon v-if="mode === 'discount'" name="check" :size="14" /></span>
      </button>
      <button type="button" data-testid="validate-mode-gifts" :class="{ active: mode === 'gifts' }" :aria-pressed="mode === 'gifts'" @click="selectMode('gifts')">
        <span class="mode-icon gifts"><Icon name="inbox" :size="20" /></span>
        <span><small>GIFT</small><strong>买赠赠品</strong><i>返回满足当前上下文的赠品明细</i></span>
        <span class="choice"><Icon v-if="mode === 'gifts'" name="check" :size="14" /></span>
      </button>
      <button type="button" data-testid="validate-mode-addon" :class="{ active: mode === 'addon' }" :aria-pressed="mode === 'addon'" @click="selectMode('addon')">
        <span class="mode-icon addon"><Icon name="layers" :size="20" /></span>
        <span><small>ADD-ON</small><strong>加价购</strong><i>先列可换购选项，再发起权威报价</i></span>
        <span class="choice"><Icon v-if="mode === 'addon'" name="check" :size="14" /></span>
      </button>
    </div>

    <Banner v-if="activeScenario.redMode === 'price'" kind="warn" data-testid="v-inventory-note">
      一口价在这里仅试算原价、减免与应付金额，不会扣减或占用秒杀库存。
    </Banner>
    <Banner v-else-if="mode === 'addon'" kind="warn" data-testid="v-inventory-note">
      加价购选项与报价均为试算，不会替用户下单，也不会占用换购库存。
    </Banner>

    <div class="validate-grid">
      <section class="context-card" @input="onContextInput">
        <header class="card-head">
          <div><span class="step">01</span><div><h2>构造决策上下文</h2><p>输入会作为真实 facts 参与资格与权益计算。</p></div></div>
          <button type="button" class="example" :disabled="busy" @click="applyExample"><Icon name="zap" :size="14" /> 填入场景示例</button>
        </header>

        <div class="form-section">
          <div class="section-label"><span>商品信息</span><small>{{ detailMode ? '订单行唯一导出汇总' : '汇总模式' }}</small></div>

          <div v-if="detailMode" class="line-editor" data-testid="v-lines">
            <div v-for="(line, index) in lines" :key="line.id" class="order-line" :data-testid="`v-line-${index}`">
              <label class="field"><span>SPU ID</span><div class="input-wrap"><input v-model="line.spuId" type="number" min="1" step="1" :aria-label="`第 ${index + 1} 行 SPU ID`" :data-testid="`v-line-spu-${index}`" /></div></label>
              <label class="field"><span>单价</span><div class="input-wrap"><b>¥</b><input v-model="line.unitPrice" type="number" min="0.01" step="0.01" :aria-label="`第 ${index + 1} 行单价`" :data-testid="`v-line-price-${index}`" /></div></label>
              <label class="field"><span>数量</span><div class="input-wrap"><input v-model="line.quantity" type="number" min="1" step="1" :aria-label="`第 ${index + 1} 行数量`" :data-testid="`v-line-qty-${index}`" /></div></label>
              <button type="button" class="remove-line" :aria-label="`删除第 ${index + 1} 行`" :data-testid="`v-line-remove-${index}`" @click="removeLine(index)"><Icon name="trash" :size="15" /></button>
            </div>
            <button type="button" class="add-line" data-testid="v-line-add" @click="addLine"><Icon name="plus" :size="14" /> 添加订单行</button>
            <div class="line-summary" data-testid="v-line-summary" aria-live="polite">
              <template v-if="parsedLineSummary">
                <span>{{ parsedLineSummary.spuIdList.length }} 个 SPU</span>
                <span>{{ parsedLineSummary.quantity }} 件</span>
                <strong>订单金额 ¥{{ fmtMoney(parsedLineSummary.orderAmount) }}</strong>
              </template>
              <span v-else>逐行填写完整后自动汇总；汇总值不可单独修改。</span>
            </div>
          </div>

          <template v-else>
            <label class="field full"><span>SPU 列表 <i>多个 ID 使用逗号分隔</i></span><div class="input-wrap"><Icon name="inbox" :size="15" /><input v-model="form.spu" placeholder="例如 990011,990012" data-testid="v-spu" /></div></label>
            <div class="field-grid">
              <label class="field"><span>订单金额</span><div class="input-wrap"><b>¥</b><input v-model="form.amount" type="number" min="0.01" step="0.01" data-testid="v-order-amount" /></div></label>
              <label class="field"><span>商品数量</span><div class="input-wrap"><Icon name="layers" :size="15" /><input v-model="form.qty" type="number" min="1" step="1" data-testid="v-quantity" /></div></label>
            </div>
          </template>
        </div>

        <div class="form-section">
          <div class="section-label"><span>用户画像</span><small>用于资格条件</small></div>
          <div class="field-grid">
            <label class="field"><span>用户 ID <i>可选</i></span><div class="input-wrap"><Icon name="badge-check" :size="15" /><input v-model="form.user" type="number" min="1" step="1" data-testid="v-user" /></div></label>
            <label class="field"><span>用户地域</span><div class="input-wrap"><Icon name="radio" :size="15" /><input v-model="form.district" placeholder="例如 110000" data-testid="v-district" /></div></label>
            <label class="field"><span>店铺 ID <i>可选</i></span><div class="input-wrap"><Icon name="workflow" :size="15" /><input v-model="form.store" type="number" min="1" step="1" placeholder="这一单来自哪个门店" data-testid="v-store" /></div></label>
          </div>
          <label class="field full"><span>用户标签 <i>多个标签使用逗号分隔</i></span><div class="input-wrap"><Icon name="layers" :size="15" /><input v-model="form.tags" placeholder="例如 vip,new" data-testid="v-tags" /></div></label>
        </div>

        <div class="actions">
          <button class="clear" type="button" :disabled="busy" @click="clearForm">清空</button>
          <button v-if="mode === 'discount'" class="run" type="button" :disabled="busy" data-testid="v-discount" @click="runDecision">
            <Icon :name="busy ? 'refresh' : 'play'" :size="16" :class="{ spinning: busy }" /> {{ busy ? '正在计算…' : '运行优惠决策' }}
          </button>
          <button v-else-if="mode === 'gifts'" class="run gift-run" type="button" :disabled="busy" data-testid="v-gifts" @click="runDecision">
            <Icon :name="busy ? 'refresh' : 'play'" :size="16" :class="{ spinning: busy }" /> {{ busy ? '正在查询…' : '查询生效赠品' }}
          </button>
          <button v-else class="run addon-run" type="button" :disabled="busy" data-testid="v-addon-options" @click="runDecision">
            <Icon :name="busy && busyAction === 'decision' ? 'refresh' : 'play'" :size="16" :class="{ spinning: busy && busyAction === 'decision' }" /> {{ busy && busyAction === 'decision' ? '正在查询…' : '查询换购选项' }}
          </button>
        </div>
      </section>

      <section class="result-card" :aria-busy="busy" aria-live="polite">
        <header class="card-head"><div><span class="step">02</span><div><h2>决策结果</h2><p>{{ mode === 'discount' ? '红包命中与优惠计算' : mode === 'gifts' ? '买赠权益与赠品明细' : '换购选项与权威报价' }}</p></div></div></header>

        <div v-if="busy" class="loading-result" role="status">
          <span><Icon name="workflow" :size="24" /></span><h3>{{ busyAction === 'quote' ? '正在重新校验报价' : '规则引擎正在决策' }}</h3><p>{{ busyAction === 'quote' ? '服务端会重新读取当前有效配置，不信任客户端价格。' : '正在筛选有效活动、计算资格条件并生成结果…' }}</p>
          <Skeleton :rows="3" />
        </div>
        <Banner v-else-if="err" kind="err" role="alert" class="result-error" data-testid="v-error"><strong>决策请求未完成</strong><span>{{ err }}</span></Banner>
        <template v-else-if="hasResult">
          <div v-if="mode === 'discount' && discountResult" class="decision-summary" :class="discountResult.hit ? 'hit' : 'miss'" data-testid="validate-result">
            <span class="decision-icon"><Icon :name="discountResult.hit ? 'badge-check' : 'x'" :size="25" /></span>
            <div><small>{{ discountResult.hit ? 'RULE MATCHED' : 'NO RULE MATCHED' }}</small><h3>{{ discountResult.hit ? '命中优惠活动' : '本次未命中优惠' }}</h3><p>{{ discountResult.hit ? `${discountResult.hitActivityName || '活动'} 已应用到当前订单` : '没有已上线活动同时满足商品与用户条件' }}</p></div>
            <strong v-if="discountResult.hit" class="hit-amount">- ¥{{ fmtMoney(discountResult.hitAmount) }}</strong>
          </div>

          <div v-else-if="mode === 'gifts' && giftResult" class="decision-summary" :class="giftResult.gifts.length ? 'hit' : 'miss'" data-testid="validate-result">
            <span class="decision-icon"><Icon name="inbox" :size="25" /></span>
            <div><small>GIFT RESULT</small><h3>{{ giftResult.gifts.length ? `返回 ${giftResult.gifts.length} 项赠品` : '没有生效赠品' }}</h3><p>决策模式：{{ giftResult.mode || '-' }}</p></div>
          </div>

          <div v-else-if="mode === 'addon' && addOnResult" class="decision-summary" :class="addOnOptions.length ? 'hit' : 'miss'" data-testid="validate-result">
            <span class="decision-icon"><Icon name="layers" :size="25" /></span>
            <div><small>ADD-ON OPTIONS</small><h3>{{ addOnOptions.length ? `返回 ${addOnOptions.length} 个换购选项` : '没有可用换购选项' }}</h3><p>选择一个选项后，服务端会重新校验并给出权威报价。</p></div>
          </div>

          <div v-if="mode === 'discount' && discountResult?.hit" class="result-metrics">
            <article><small>命中活动 ID</small><strong class="mono">{{ discountResult.hitActivityId }}</strong></article>
            <article><small>合并策略</small><strong>{{ discountResult.strategy || '-' }}</strong></article>
            <article><small>决策模式</small><strong>{{ discountResult.mode || '-' }}</strong></article>
          </div>

          <Card v-if="priceBreakdown" title="一口价试算" data-testid="v-price-breakdown">
            <div class="price-breakdown">
              <span><small>原价</small><strong>¥{{ fmtMoney(priceBreakdown.original) }}</strong></span>
              <span><small>减免</small><strong>- ¥{{ fmtMoney(priceBreakdown.discount) }}</strong></span>
              <span><small>应付</small><strong>¥{{ fmtMoney(priceBreakdown.payable) }}</strong></span>
            </div>
          </Card>

          <Card v-if="mode === 'gifts' && giftResult?.gifts.length" title="赠品明细">
            <div class="gift-list">
              <div v-for="(gift, index) in giftResult.gifts" :key="`${gift.batchId || ''}-${index}`" class="gift-row"><span>{{ index + 1 }}</span><div><strong>{{ gift.giftName || '未命名赠品' }}</strong><small>{{ gift.giftType || gift.rightType || '赠品' }}</small></div><b>×{{ gift.giftNum ?? 0 }} · {{ fmtMoney(gift.absoluteAmount) }}</b></div>
            </div>
          </Card>

          <Card v-if="mode === 'addon' && addOnOptions.length" title="选择换购品">
            <fieldset class="addon-options">
              <legend class="sr-only">可用换购选项</legend>
              <label v-for="(option, index) in addOnOptions" :key="`${option.activityId}-${option.itemName}`" class="addon-option" :class="{ selected: selectedOptionIndex === index }">
                <input v-model="selectedOptionIndex" type="radio" name="addon-option" :value="index" :data-testid="`v-addon-option-${index}`" @change="selectAddOnOption" />
                <span><strong>{{ option.itemName }}</strong><small>{{ option.activityName }} · {{ option.activityId }}</small></span>
                <b>加 ¥{{ fmtMoney(option.addOnPrice) }}</b>
              </label>
            </fieldset>
            <button class="quote-button" type="button" :disabled="busy || selectedOptionIndex === null" data-testid="v-addon-quote" @click="requestQuote"><Icon name="badge-check" :size="15" /> 获取权威报价</button>
          </Card>

          <Banner v-if="quoteConflict" kind="warn" role="alert" data-testid="v-addon-conflict">
            <strong>报价已失效（409）</strong><span>{{ quoteConflict }}。请重新查询换购选项后再试。</span>
          </Banner>
          <Card v-if="addOnQuoteResult" title="权威报价" data-testid="v-addon-quote-result">
            <div class="quote-result"><span><Icon name="badge-check" :size="22" /></span><div><strong>{{ addOnQuoteResult.itemName }}</strong><small>活动 {{ addOnQuoteResult.activityId }} · 本次只报价，未下单、未占库存</small></div><b>加 ¥{{ fmtMoney(addOnQuoteResult.addOnPrice) }}</b></div>
          </Card>

          <div class="trace-panel">
            <div class="trace-head"><span><Icon name="workflow" :size="15" /> 决策轨迹</span><small>{{ traces.length }} STEPS</small></div>
            <div v-if="traces.length" class="traces">
              <div v-for="(trace, index) in traces" :key="index" class="trace-row"><span><i />{{ String(index + 1).padStart(2, '0') }}</span><p>{{ trace }}</p></div>
            </div>
            <div v-else class="no-trace">本次响应没有返回决策轨迹</div>
          </div>
        </template>
        <EmptyState v-else icon="scale" title="等待运行决策" hint="选择玩法场景、填写订单和用户上下文，结果会在这里显示；场景不会强制命中活动。" />
      </section>
    </div>
  </section>
</template>

<style scoped>
.scenario-panel { display: grid; grid-template-columns: minmax(220px, .55fr) minmax(280px, 1fr); gap: var(--sp-3); align-items: center; margin-bottom: var(--sp-3); padding: var(--sp-3) var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); box-shadow: var(--shadow-sm); }
.scenario-panel > div:first-child { display: flex; align-items: center; gap: var(--sp-2); }
.scenario-panel label { color: var(--text-soft); font-size: var(--fs-xs); font-weight: var(--fw-bold); white-space: nowrap; }
.scenario-panel select { min-width: 0; flex: 1; min-height: 40px; padding: 0 var(--sp-3); border: 1px solid var(--border-ctl); border-radius: var(--radius-sm); background: var(--bg-soft); color: var(--text); font: inherit; font-size: var(--fs-sm); }
.scenario-panel select:focus { outline: 0; border-color: var(--accent); box-shadow: var(--focus-ring); }
.scenario-panel p { margin: 0; color: var(--text-soft); font-size: var(--fs-xs); line-height: 1.55; }
.scenario-panel :deep(.banner) { grid-column: 1 / -1; margin: 0; }

.mode-picker { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--sp-3); margin-bottom: var(--sp-4); }
.mode-picker > button { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--sp-3); min-width: 0; padding: var(--sp-3) var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); color: var(--text); cursor: pointer; text-align: left; box-shadow: var(--shadow-sm); }
.mode-picker > button:hover { border-color: var(--border-strong); }
.mode-picker > button.active { border-color: var(--accent); background: linear-gradient(100deg, var(--accent-soft), var(--bg-elev)); box-shadow: 0 0 0 1px var(--accent-line); }
.mode-icon { display: inline-flex; align-items: center; justify-content: center; width: 42px; height: 42px; border-radius: 12px; background: var(--accent-soft); color: var(--accent); }
.mode-icon.gifts { background: var(--blue-soft); color: var(--blue); }
.mode-icon.addon { background: var(--gold-soft); color: var(--gold); }
.mode-picker small, .mode-picker strong, .mode-picker i { display: block; }
.mode-picker small { color: var(--accent); font-size: var(--fs-2xs); font-style: normal; letter-spacing: .08em; }
.mode-picker strong { margin-top: 2px; font-size: var(--fs-sm); }
.mode-picker i { overflow: hidden; margin-top: 2px; color: var(--text-faint); font-size: var(--fs-2xs); font-style: normal; text-overflow: ellipsis; white-space: nowrap; }
.choice { display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; border: 1px solid var(--border-strong); border-radius: 50%; }
.active .choice { border-color: var(--accent); background: var(--accent); color: var(--text-invert); }

.validate-grid { display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr); gap: var(--sp-4); align-items: stretch; }
.context-card, .result-card { min-width: 0; overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); box-shadow: var(--shadow-sm); }
.card-head { display: flex; align-items: center; justify-content: space-between; min-height: 72px; padding: var(--sp-3) var(--sp-4); border-bottom: 1px solid var(--border); background: var(--bg-soft); }
.card-head > div { display: flex; align-items: center; gap: var(--sp-3); }
.step { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; border-radius: 9px; background: var(--accent-soft); color: var(--accent); font-size: var(--fs-xs); font-weight: var(--fw-bold); font-variant-numeric: tabular-nums; }
.card-head h2 { margin: 0; font-size: var(--fs-md); }
.card-head p { margin: 1px 0 0; color: var(--text-faint); font-size: var(--fs-2xs); }
.example { display: inline-flex; align-items: center; gap: var(--sp-1); padding: var(--sp-2); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--accent); cursor: pointer; font: inherit; font-size: var(--fs-xs); }
.example:disabled { cursor: wait; opacity: .6; }

.form-section { padding: var(--sp-4); border-bottom: 1px solid var(--border); }
.section-label { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--sp-3); }
.section-label span { font-size: var(--fs-xs); font-weight: var(--fw-bold); letter-spacing: .06em; text-transform: uppercase; }
.section-label small { color: var(--text-faint); font-size: var(--fs-2xs); }
.field-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); }
.field { display: flex; flex-direction: column; gap: var(--sp-1); min-width: 0; }
.field.full + .field-grid, .field-grid + .field.full { margin-top: var(--sp-3); }
.field > span { display: flex; justify-content: space-between; color: var(--text-soft); font-size: var(--fs-xs); font-weight: var(--fw-medium); }
.field > span i { color: var(--text-faint); font-size: var(--fs-2xs); font-style: normal; font-weight: var(--fw-medium); }
.input-wrap { display: flex; align-items: center; gap: var(--sp-2); min-height: 40px; padding: 0 var(--sp-3); border: 1px solid var(--border-ctl); border-radius: var(--radius-sm); background: var(--bg-soft); color: var(--text-faint); }
.input-wrap:focus-within { border-color: var(--accent); background: var(--bg-elev); box-shadow: var(--focus-ring); }
.input-wrap input { width: 100%; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--text); font: inherit; font-size: var(--fs-sm); }
.input-wrap b { color: var(--text-soft); font-size: var(--fs-sm); }

.line-editor { display: flex; flex-direction: column; gap: var(--sp-2); }
.order-line { display: grid; grid-template-columns: 1fr 1fr .75fr auto; gap: var(--sp-2); align-items: end; padding: var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-soft); }
.remove-line { display: inline-flex; align-items: center; justify-content: center; width: 40px; height: 40px; border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--err); cursor: pointer; }
.add-line { display: inline-flex; align-items: center; justify-content: center; gap: var(--sp-1); align-self: flex-start; min-height: 36px; padding: 0 var(--sp-3); border: 1px dashed var(--border-strong); border-radius: var(--radius-sm); background: transparent; color: var(--accent); cursor: pointer; font: inherit; font-size: var(--fs-xs); }
.line-summary { display: flex; flex-wrap: wrap; gap: var(--sp-3); padding: var(--sp-2) var(--sp-3); border-radius: var(--radius-sm); background: var(--blue-soft); color: var(--blue); font-size: var(--fs-xs); }
.line-summary strong { margin-left: auto; }

.actions { display: flex; gap: var(--sp-2); justify-content: flex-end; padding: var(--sp-4); }
.actions button { min-height: 42px; border-radius: var(--radius-sm); cursor: pointer; font: inherit; font-size: var(--fs-xs); }
.actions button:disabled { cursor: wait; opacity: .6; }
.clear { padding: 0 var(--sp-4); border: 1px solid var(--border); background: var(--bg-elev); color: var(--text-soft); }
.run { display: inline-flex; align-items: center; justify-content: center; gap: var(--sp-2); min-width: 190px; padding: 0 var(--sp-4); border: 0; background: linear-gradient(100deg, var(--accent), var(--accent-2)); color: var(--text-invert); font-weight: var(--fw-semibold); box-shadow: 0 8px 18px color-mix(in srgb, var(--accent) 20%, transparent); }
.gift-run { background: linear-gradient(100deg, var(--blue), var(--accent-2)); }
.addon-run { background: linear-gradient(100deg, var(--gold), var(--accent-2)); }
.spinning { animation: spin .9s linear infinite; }

.loading-result { display: flex; min-height: 470px; flex-direction: column; align-items: center; justify-content: center; padding: var(--sp-6); text-align: center; }
.loading-result > span { display: inline-flex; align-items: center; justify-content: center; width: 58px; height: 58px; border-radius: 18px; background: var(--accent-soft); color: var(--accent); animation: breathe 1s ease-in-out infinite alternate; }
.loading-result h3 { margin: var(--sp-3) 0 var(--sp-1); font-size: var(--fs-md); }
.loading-result p { max-width: 360px; margin: 0; color: var(--text-faint); font-size: var(--fs-xs); }
.loading-result > div { width: min(280px, 80%); margin-top: var(--sp-5); }
.result-error { display: flex; min-height: 110px; flex-direction: column; justify-content: center; margin: var(--sp-4); }
.result-error span { margin-top: 2px; font-size: var(--fs-xs); }
.decision-summary { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--sp-3); margin: var(--sp-4); padding: var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg); }
.decision-summary.hit { border-color: color-mix(in srgb, var(--green) 28%, var(--border)); background: var(--green-soft); }
.decision-summary.miss { background: var(--bg-soft); }
.decision-icon { display: inline-flex; align-items: center; justify-content: center; width: 46px; height: 46px; border-radius: 14px; background: var(--bg-elev); color: var(--green); }
.miss .decision-icon { color: var(--text-faint); }
.decision-summary small { color: var(--green); font-size: var(--fs-2xs); letter-spacing: .08em; }
.decision-summary h3 { margin: 2px 0; font-size: var(--fs-md); }
.decision-summary p { margin: 0; color: var(--text-soft); font-size: var(--fs-2xs); }
.hit-amount { color: var(--green); font-size: 20px; font-variant-numeric: tabular-nums; }
.result-metrics { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--sp-2); margin: 0 var(--sp-4) var(--sp-4); }
.result-metrics article { min-width: 0; padding: var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-soft); }
.result-metrics small, .result-metrics strong { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.result-metrics small { color: var(--text-faint); font-size: var(--fs-2xs); }
.result-metrics strong { margin-top: 2px; font-size: var(--fs-xs); }
.mono { font-family: var(--mono); }
.result-card :deep(.card) { margin: 0 var(--sp-4) var(--sp-4); }
.price-breakdown { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--sp-2); }
.price-breakdown span { padding: var(--sp-2); border-radius: var(--radius-sm); background: var(--bg-soft); }
.price-breakdown small, .price-breakdown strong { display: block; }
.price-breakdown small { color: var(--text-faint); font-size: var(--fs-2xs); }
.price-breakdown strong { margin-top: 2px; font-size: var(--fs-sm); }
.gift-list { display: flex; flex-direction: column; }
.gift-row { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: var(--sp-2); padding: var(--sp-2) 0; border-bottom: 1px solid var(--border); }
.gift-row:last-child { border-bottom: 0; }
.gift-row > span { display: inline-flex; align-items: center; justify-content: center; width: 26px; height: 26px; border-radius: 7px; background: var(--bg-soft); color: var(--text-faint); font-size: var(--fs-2xs); }
.gift-row strong, .gift-row small { display: block; }
.gift-row strong { font-size: var(--fs-xs); }
.gift-row small { color: var(--text-faint); font-size: var(--fs-2xs); }
.gift-row b { color: var(--text-soft); font-size: var(--fs-2xs); }

.addon-options { display: flex; flex-direction: column; gap: var(--sp-2); margin: 0; padding: 0; border: 0; }
.addon-option { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: var(--sp-2); align-items: center; padding: var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-soft); cursor: pointer; }
.addon-option.selected { border-color: var(--accent); background: var(--accent-soft); }
.addon-option strong, .addon-option small { display: block; }
.addon-option strong { font-size: var(--fs-xs); }
.addon-option small { overflow-wrap: anywhere; color: var(--text-faint); font-size: var(--fs-2xs); }
.addon-option b { color: var(--gold); font-size: var(--fs-xs); }
.quote-button { display: inline-flex; align-items: center; justify-content: center; gap: var(--sp-1); width: 100%; min-height: 40px; margin-top: var(--sp-3); border: 0; border-radius: var(--radius-sm); background: var(--accent); color: var(--text-invert); cursor: pointer; font: inherit; font-size: var(--fs-xs); font-weight: var(--fw-semibold); }
.quote-button:disabled { cursor: not-allowed; opacity: .5; }
.result-card > :deep(.banner) { margin: var(--sp-4); }
.quote-result { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: var(--sp-3); align-items: center; }
.quote-result > span { color: var(--green); }
.quote-result strong, .quote-result small { display: block; }
.quote-result small { margin-top: 2px; color: var(--text-faint); font-size: var(--fs-2xs); }
.quote-result b { color: var(--gold); font-size: var(--fs-md); }

.trace-panel { margin: 0 var(--sp-4) var(--sp-4); overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-soft); }
.trace-head { display: flex; align-items: center; justify-content: space-between; padding: var(--sp-2) var(--sp-3); border-bottom: 1px solid var(--border); color: var(--text-soft); font-size: var(--fs-xs); font-weight: var(--fw-semibold); }
.trace-head span { display: inline-flex; align-items: center; gap: var(--sp-2); }
.trace-head small { color: var(--text-faint); font-size: var(--fs-2xs); font-variant-numeric: tabular-nums; }
.traces { padding: var(--sp-2) var(--sp-3); }
.trace-row { display: grid; grid-template-columns: 34px 1fr; gap: var(--sp-2); min-height: 36px; }
.trace-row > span { position: relative; color: var(--accent); font-size: var(--fs-2xs); font-variant-numeric: tabular-nums; }
.trace-row > span::after { content: ''; position: absolute; top: 15px; bottom: -4px; left: 3px; width: 1px; background: var(--border); }
.trace-row:last-child > span::after { display: none; }
.trace-row > span i { display: inline-block; width: 7px; height: 7px; margin-right: 5px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
.trace-row p { min-width: 0; margin: 0; overflow-wrap: anywhere; color: var(--text-soft); font-size: var(--fs-2xs); line-height: 1.55; }
.no-trace { padding: var(--sp-4); color: var(--text-faint); font-size: var(--fs-xs); text-align: center; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }

@media (max-width: 1100px) {
  .mode-picker { grid-template-columns: 1fr; }
  .validate-grid { grid-template-columns: 1fr; }
  .loading-result { min-height: 300px; }
}
@media (max-width: 840px) {
  /* 768px 时外壳仍保留 212px 常驻侧栏，内容宽度不足以承载双列场景面板。 */
  .scenario-panel { grid-template-columns: 1fr; }
  .scenario-panel :deep(.banner) { grid-column: 1; }
  .scenario-panel > div:first-child { align-items: stretch; flex-direction: column; }
  .order-line { grid-template-columns: 1fr 1fr; }
  .remove-line { width: 100%; }
}
@media (max-width: 700px) {
  .field-grid { grid-template-columns: 1fr; }
  .actions { align-items: stretch; flex-direction: column; }
  .actions button { width: 100%; }
  .decision-summary { grid-template-columns: auto minmax(0, 1fr); }
  .hit-amount { grid-column: 2; }
  .result-metrics, .price-breakdown { grid-template-columns: 1fr; }
  .addon-option, .quote-result { grid-template-columns: auto minmax(0, 1fr); }
  .addon-option b, .quote-result b { grid-column: 2; }
}
</style>
