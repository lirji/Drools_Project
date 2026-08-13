<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { createActivity, getDetail, previewTree } from '../activityApi'
import { useDictStore } from '@/stores/useDictStore'
import { useDistrictStore } from '@/stores/useDistrictStore'
import { useAuthStore } from '@/auth/useAuthStore'
import { useToast } from '@/shared/useToast'
import { useConfirm } from '@/shared/useConfirm'
import { errText } from '@/shared/apiClient'
import {
  uuid, numOrNull, toEpoch, toLocalInput, isoToLocal, cleanLadder,
  pruneTree, assignIds, emptyGroup, validateTree, invalidLeafReasons, stripDistrictNodes,
  benefitDraftFromRule, benefitRequestFields, type BenefitForm, type LadderRow,
} from '../logic'
import type { ActivityCreateRequest, FieldDict, GroupNode } from '@/shared/types'
import ConditionGroup from '../condition-tree/ConditionGroup.vue'
import DistrictPicker from '../district/DistrictPicker.vue'
import { parseCodes, toCsv, MAX_DISTRICTS } from '../district/districtLogic'
import DynRowTable from '../DynRowTable.vue'
import StoreProductPicker from '../binding/StoreProductPicker.vue'
import { newPairs } from '../binding/storeProductPickerLogic'
import { normalizeTiers, plainLanguage } from '../benefit/tierLogic'
import FixedForm from '../benefit/forms/FixedForm.vue'
import RandomForm from '../benefit/forms/RandomForm.vue'
import LadderForm from '../benefit/forms/LadderForm.vue'
import RatioForm from '../benefit/forms/RatioForm.vue'
import PriceForm from '../benefit/forms/PriceForm.vue'
import NthForm from '../benefit/forms/NthForm.vue'
import { findPlaybook, CREATABLE_ACTIVITY_TYPES } from '../playbooks'
import Card from '@/shared/ui/Card.vue'
import Kv from '@/shared/ui/Kv.vue'
import Banner from '@/shared/ui/Banner.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import Segmented from '@/shared/ui/Segmented.vue'
import Section from '@/shared/ui/Section.vue'
import Button from '@/shared/ui/Button.vue'
import Icon from '@/shared/ui/Icon.vue'
import Skeleton from '@/shared/ui/Skeleton.vue'

const route = useRoute()
const router = useRouter()
const dict = useDictStore()
const districtStore = useDistrictStore()
const auth = useAuthStore()
const toast = useToast()
const { confirm } = useConfirm()
const editId = computed(() => (route.name === 'activity-edit' ? (route.params.id as string) : null))

interface Draft {
  activityId: string | null
  requestId: string
  activityType: number
  /** random 是一等形态；takeType 仅是提交时由形态导出的存储字段，不在 Draft 里形成第二权威。 */
  redMode: BenefitForm
  /** 第 N 件折的 N（≥2） */
  nth: number | string
  bindMode: 'manual' | 'pool'
  areaType: number
  name: string
  bizLine: string
  rule: string
  priority: number | string
  inventory: number | string
  startLocal: string
  endLocal: string
  districtIds: string
  amount: number | string
  maxDiscount: number | string
  /** 随机金额区间（元）。仅 redMode=random 时有意义。 */
  rangeMin: number | string
  rangeMax: number | string
  strategy: string
  ladder: LadderRow[]
  gifts: Array<Record<string, unknown>>
  spu: Array<{ storeId: number | string; spuId: number | string }>
  pool: Array<{ poolId: number | string }>
  tree: GroupNode
}

function newDraft(): Draft {
  return {
    activityId: null, requestId: uuid(),
    activityType: 1, redMode: 'fixed', bindMode: 'manual', areaType: 1,
    name: '', bizLine: 'mall', rule: '', priority: 1, inventory: 100,
    startLocal: toLocalInput(Date.now() - 3600000), endLocal: toLocalInput(Date.now() + 7 * 86400000),
    districtIds: '', amount: '', maxDiscount: '', rangeMin: '', rangeMax: '', nth: 2, strategy: 'MAX',
    ladder: [], gifts: [], spu: [{ storeId: 1, spuId: '' }], pool: [{ poolId: '' }],
    tree: emptyGroup(),
  }
}

const dr = reactive<Draft>(newDraft())
/** 人话预览：把档位参数实时翻译成运营看得懂的一句话（PR-4）。 */
const tierPlain = computed(() => plainLanguage(normalizeTiers(dr.ladder), '取最高档，不与其它满减叠加'))

/**
 * 折扣的人话预览。与 TierRuler 的人话预览同一个理由：
 * 「折数 8 / 封顶 50」是两个参数，运营真正要确认的是「这张券最多能减多少、什么时候封顶」。
 * 说清封顶在多少订单额上开始生效，比只回显参数有用得多。
 */
const ratioPlain = computed(() => {
  const zhe = Number(dr.amount)
  const cap = Number(dr.maxDiscount)
  if (!zhe || Number.isNaN(zhe) || zhe <= 0 || zhe >= 10) return '填入折数后这里会显示这张券的人话说明。'
  const offRate = (10 - zhe) / 10
  const pct = Math.round(offRate * 1000) / 10
  if (!cap || Number.isNaN(cap) || cap <= 0) return `订单打 ${zhe} 折（减免 ${pct}%）。还需填封顶减免额。`
  const threshold = Math.ceil(cap / offRate)
  return `订单打 ${zhe} 折，即减免 ${pct}%，最多减 ${cap.toLocaleString('zh-CN')} 元；`
    + `订单满 ${threshold.toLocaleString('zh-CN')} 元起减免就到顶了。不足一分的部分向下取整。`
})

const submitting = ref(false)
const submitErr = ref('')
const saved = ref<{ activityId: string; version: number; autoBoundCount: number; idempotentHit: boolean } | null>(null)
const dirty = ref(false)
const initialLoading = ref(true)
const initialErr = ref('')
const dictWarning = ref('')
const dictRetrying = ref(false)
const districtLoading = ref(false)

/**
 * 草稿里 `districtIds` 仍是 CSV 字符串（`Draft.districtIds` / 提交映射 / 回读三处一个字节都没动），
 * 组件内部一律用 `string[]`——集合运算写起来才不至于满屏 split/join。转换只在这一处边界发生。
 *
 * setter 里**必须**显式 markDirty：DistrictPicker 把 click 截在了自己根上
 * （否则光是点开面板就会重铸幂等 requestId、清掉刚保存的成功卡），
 * 所以 EditorView `.form` 上那个 `@click` 冒泡收不到它。改了才算改，这是唯一的脏值来源。
 */
const districtCodes = computed<string[]>({
  get: () => parseCodes(dr.districtIds),
  set: (v: string[]) => { dr.districtIds = toCsv(v); markDirty() },
})

/**
 * 字典**按需拉**，不在 initialize 里无条件拉。
 *
 * 「全国」是默认值，也是全部六条经过编辑页的 e2e 走的那条路——它们只填名称/金额/SPU。
 * 无条件拉会给这些路径凭空加一个网络依赖，也会打穿 EditorView.test.ts 里那三个
 * 「按 URL 分派 + 其余兜底」的 fetch mock。
 */
watch(() => dr.areaType, async (t) => {
  if (t !== 2 || districtStore.items) return
  districtLoading.value = true
  try { await districtStore.load() } finally { districtLoading.value = false }
}, { immediate: true })
/** 本次表单是从哪个玩法模板起步的（PR-6）。只作提示，不影响提交内容 */
const appliedPlaybook = ref('')
const submitAttempted = ref(false)
const previewState = ref<{ kind: 'idle' | 'pending' | 'ok' | 'err'; msg: string; drl?: string }>({ kind: 'idle', msg: '' })
let initialCtrl: AbortController | null = null
let previewCtrl: AbortController | null = null
let submitCtrl: AbortController | null = null
let redModeUndoToastId: number | null = null
/** 进 price 前的库存暂存：离开 price 时恢复，别把 disabled 的声明式库存清成永远填不回的空 */
let inventoryBeforePrice: number | string | null = null
// 条件树逐叶行内错误：预览/提交尝试后才显（避免打字中闪红），之后随修复实时收敛。
const showTreeErrors = ref(false)
const treeErrors = computed(() =>
  showTreeErrors.value ? invalidLeafReasons(dr.tree, dictData.value?.operators || []) : undefined,
)

const dictData = computed(() => dict.cache['__default__'] || null)
// 白名单只有一份（playbooks.ts 的 CREATABLE_ACTIVITY_TYPES，与写平面 validateCommon 同源）。
// 从前这里写死 1/5，而玩法目录另有一份手抄的 [1,5,6]——两份白名单不一致，正是加价购模板
// 能跳进这个编辑器、却选不中自己类型的原因。
const enabledTypes = computed(() =>
  (dictData.value?.activityTypes || []).filter((t) => CREATABLE_ACTIVITY_TYPES.includes(t.code)))
const strategies = computed(() => dictData.value?.strategies || [])

// 就地校验
const validationErrs = computed(() => {
  const errs: string[] = []
  if (!dictData.value) errs.push('字段配置未加载，暂时不能保存')
  if (!dr.name.trim()) errs.push('活动名称必填')
  if (!dr.startLocal) errs.push('开始时间必填')
  if (!dr.endLocal) errs.push('结束时间必填')
  if (dr.startLocal && dr.endLocal && toEpoch(dr.startLocal)! >= toEpoch(dr.endLocal)!) errs.push('开始时间须早于结束时间')
  // 「指定地域」却一个都没选，落库后详情页会回显成「指定地域」——看着像配好了，实际是空投放。
  if (dr.areaType === 2 && districtCodes.value.length === 0) errs.push('指定地域时至少选择一个行政区（或切回「全国」）')
  // 上限来自 activity_manage.district_ids 的列宽（varchar(1024)）。拦在这里，就不用等后端 400。
  if (districtCodes.value.length > MAX_DISTRICTS) errs.push(`投放地域最多 ${MAX_DISTRICTS} 个，当前 ${districtCodes.value.length} 个`)
  if (dr.activityType === 1 && dr.redMode === 'fixed' && (dr.amount === '' || dr.amount == null)) errs.push('固定红包金额必填')
  if (dr.activityType === 1 && dr.redMode === 'random') {
    const minMissing = dr.rangeMin === '' || dr.rangeMin == null
    const maxMissing = dr.rangeMax === '' || dr.rangeMax == null
    if (minMissing) errs.push('随机金额下限必填')
    if (maxMissing) errs.push('随机金额上限必填')
    if (!minMissing && !maxMissing) {
      const min = Number(dr.rangeMin), max = Number(dr.rangeMax)
      if (!Number.isFinite(min) || !Number.isFinite(max) || min < 0 || min > max) {
        errs.push('随机金额区间须满足 0 ≤ 下限 ≤ 上限')
      }
    }
  }
  if (dr.activityType === 1 && dr.redMode === 'ladder' && !cleanLadder(dr.ladder).length) errs.push('阶梯档至少一档有奖励')
  // 一口价是「卖多少」：0 等于白送、负数等于倒贴，两者都不是运营的本意
  if (dr.activityType === 1 && dr.redMode === 'price' && !(Number(dr.amount) > 0)) errs.push('一口价必须大于 0')
  if (dr.activityType === 1 && dr.redMode === 'price') {
    const inventory = Number(dr.inventory)
    if (!Number.isInteger(inventory) || inventory < 1) errs.push('秒杀库存必须为至少 1 件的整数')
  }
  if (dr.activityType === 1 && dr.redMode === 'nth') {
    if (!(Number(dr.amount) > 0 && Number(dr.amount) < 10)) errs.push('第 N 件折的折数必须在 (0,10)')
    if (!(Number(dr.nth) >= 2)) errs.push('第 N 件折的 N 必须 ≥ 2（1 等于全场打折）')
  }
  if (dr.activityType === 1 && dr.redMode === 'ratio') {
    const zhe = Number(dr.amount)
    if (dr.amount === '' || dr.amount == null || Number.isNaN(zhe)) errs.push('折数必填')
    else if (zhe <= 0 || zhe >= 10) errs.push('折数须在 0 与 10 之间（8 = 八折；10 折=不打折、0 折=白送）')
    // 封顶是硬要求不是建议：打 8 折在一笔 10 万的订单上就是 2 万
    const cap = Number(dr.maxDiscount)
    if (dr.maxDiscount === '' || dr.maxDiscount == null || Number.isNaN(cap) || cap <= 0) errs.push('折扣券必须填封顶减免额（不封顶等于无上限支出）')
  }
  if (dr.activityType === 5 && !dr.gifts.length) errs.push('买赠活动至少需配置一个赠品')
  // 加价购：这三条**逐条对着决策侧的一行代码**（见后端 validateAddOnItems）。
  // 前端先拦一次是为了让运营当场看见问题，而不是填完整张表在保存时吃 400。
  if (dr.activityType === 6) {
    if (!dr.gifts.length) errs.push('加价购至少需配置一个换购品（一个都没有等于上线后没有可换购选项）')
    else {
      const names = dr.gifts.map((g) => String(g.giftName ?? '').trim())
      if (names.some((n) => !n)) errs.push('换购品名称必填——第二阶段报价按品名匹配选项')
      const filled = names.filter(Boolean)
      if (new Set(filled).size !== filled.length) errs.push('换购品名称不能重复（重名的那个永远选不中）')
      // 决策侧对 <=0 的行是静默 continue：不拦住的话，运营配的选项会一声不响地消失
      if (dr.gifts.some((g) => !(Number(g.absoluteAmount) > 0))) errs.push('换购品的加价金额必须大于 0')
    }
  }
  const treeErrs = validateTree(pruneTree(dr.tree), dictData.value?.operators || [])
  if (treeErrs.length) errs.push('条件树有 ' + treeErrs.length + ' 处未填完整')
  return errs
})
const formValid = computed(() => validationErrs.value.length === 0)
const completionChecks = computed(() => [
  { label: '基础信息', done: !!dr.name.trim() && !!dr.startLocal && !!dr.endLocal },
  { label: dr.activityType === 1 ? '红包规则' : dr.activityType === 6 ? '换购品' : '赠品明细', done: dr.activityType === 1
      ? (dr.redMode === 'ladder' ? cleanLadder(dr.ladder).length > 0
        : dr.redMode === 'ratio' ? dr.amount !== '' && dr.maxDiscount !== ''
        : dr.redMode === 'nth' ? dr.amount !== '' && dr.nth !== ''
        : dr.redMode === 'price' ? dr.amount !== '' && Number.isInteger(Number(dr.inventory)) && Number(dr.inventory) >= 1
        : dr.redMode === 'random'
          ? dr.rangeMin !== '' && dr.rangeMax !== '' && Number.isFinite(Number(dr.rangeMin))
            && Number.isFinite(Number(dr.rangeMax)) && Number(dr.rangeMin) >= 0 && Number(dr.rangeMin) <= Number(dr.rangeMax)
          : dr.amount !== '')
      : dr.gifts.length > 0 },
  { label: '商品绑定', done: dr.bindMode === 'manual' ? dr.spu.some((item) => item.spuId !== '') : dr.pool.some((item) => item.poolId !== '') },
  { label: '资格条件', done: validateTree(pruneTree(dr.tree), dictData.value?.operators || []).length === 0 },
])
const completionPercent = computed(() => Math.round(completionChecks.value.filter((item) => item.done).length / completionChecks.value.length * 100))

/**
 * 切活动类型。**买赠与加价购共用 gifts 这张表，但金额列的含义不同**——
 * 买赠的 `absoluteAmount` 是赠品价值（可以是 0），加价购的是「加多少钱换购」（必须 > 0）。
 *
 * <p>带着已填的行切过去等于静默换语义，而且 6→5 那个方向是**无声**的：
 * 9.9 元的加价额会变成 9.9 元的赠品价值，写平面照收不误。所以只要涉及加价购就清空并说明，
 * 让运营重新填一遍——这比让他保存出一个语义错了的活动便宜得多。
 */
function changeActivityType(next: number): void {
  const prev = dr.activityType
  if (prev !== next && dr.gifts.length && (prev === 6 || next === 6)) {
    dr.gifts = []
    toast.warn('已清空明细行：买赠的「金额」是赠品价值，加价购的是加价金额，含义不同需重填')
  }
  dr.activityType = next
  if (prev !== next) appliedPlaybook.value = ''
  markDirty()
}

const redModeLabels: Record<Draft['redMode'], string> = {
  fixed: '固定金额', random: '随机金额', ladder: '阶梯分档', ratio: '折扣', price: '一口价', nth: '第 N 件折',
}

/**
 * 切权益形态时，不能把同一个数字原样搬到另一种单位下。
 * amount 在六种形态里分别可能是金额、折数或卖价；inventory/nth/range 也有只属于某一形态的语义。
 * 统一在这里清理，避免模板或上一次切换留下的隐藏值在提交时重新出现。
 */
function changeRedMode(next: Draft['redMode']): void {
  const prev = dr.redMode
  if (prev === next) return
  const before = {
    redMode: prev,
    amount: dr.amount,
    maxDiscount: dr.maxDiscount,
    inventory: dr.inventory,
    nth: dr.nth,
    rangeMin: dr.rangeMin,
    rangeMax: dr.rangeMax,
    ladder: dr.ladder.map((tier) => ({ ...tier })),
    appliedPlaybook: appliedPlaybook.value,
  }
  // 空白草稿切形态时其实什么都没清——文案不该谎称「旧值已清空」。
  const hadValues = before.amount !== ''
    || ((prev === 'random' || next === 'random') && (before.rangeMin !== '' || before.rangeMax !== ''))
    || ((prev === 'ladder' || next === 'ladder') && before.ladder.length > 0)
    || ((prev === 'ratio' || next === 'ratio') && before.maxDiscount !== '')

  dr.redMode = next
  dr.amount = ''
  if (prev === 'random' || next === 'random') {
    dr.rangeMin = ''
    dr.rangeMax = ''
  }
  if (prev === 'ladder' || next === 'ladder') dr.ladder = []
  if (prev === 'ratio' || next === 'ratio') dr.maxDiscount = ''
  // inventory 是双语义字段：price 下是秒杀库存（可编辑），其它形态下是声明式展示（disabled）。
  // 进 price 时暂存旧值并清空让运营重填；离开 price 时**恢复暂存值**——清成空会让一个
  // 无法编辑的 disabled 框永远留空，提交时把原有库存静默抹成 null。
  if (next === 'price') { inventoryBeforePrice = dr.inventory; dr.inventory = '' }
  else if (prev === 'price') {
    // 有暂存（本会话从别的形态切进来的）→ 恢复暂存；空暂存回默认 100。
    // 没暂存（模板/回读直接落在 price）→ 保留当前值：它就是这个活动真实的库存数，别用默认值顶掉。
    if (inventoryBeforePrice !== null) dr.inventory = inventoryBeforePrice === '' ? 100 : inventoryBeforePrice
    else if (dr.inventory === '') dr.inventory = 100
    inventoryBeforePrice = null
  }
  if (prev === 'nth' || next === 'nth') dr.nth = ''
  appliedPlaybook.value = ''
  markDirty()

  if (redModeUndoToastId !== null) toast.dismiss(redModeUndoToastId)
  const msg = hadValues
    ? `已从「${redModeLabels[prev]}」切换为「${redModeLabels[next]}」，不同含义的旧值已清空`
    : `已从「${redModeLabels[prev]}」切换为「${redModeLabels[next]}」`
  const toastId = toast.show(msg, {
    kind: 'warn', ttl: 8000, countdown: true,
    actions: [{
      label: '撤销', testid: 'undo-red-mode',
      onClick: () => {
        const { appliedPlaybook: previousPlaybook, ...previousDraft } = before
        Object.assign(dr, previousDraft, { ladder: before.ladder.map((tier) => ({ ...tier })) })
        appliedPlaybook.value = previousPlaybook
        if (redModeUndoToastId === toastId) redModeUndoToastId = null
        markDirty()
      },
    }],
    onExpire: () => { if (redModeUndoToastId === toastId) redModeUndoToastId = null },
  })
  redModeUndoToastId = toastId
}

function markDirty(): void {
  // 保存成功代表这次逻辑请求已经被幂等表消费。下一次真实编辑必须新铸 key；
  // 失败重试时 saved 为空，因此仍沿用原 key，继续享受幂等保护。
  if (saved.value) {
    dr.requestId = uuid()
    saved.value = null
  }
  dirty.value = true
  submitAttempted.value = false
  if (previewState.value.kind !== 'idle') {
    previewCtrl?.abort()
    previewCtrl = null
    previewState.value = { kind: 'idle', msg: '' }
  }
}

function onFormClick(event: MouseEvent): void {
  const button = (event.target as Element | null)?.closest('button')
  if (button && button.dataset.testid !== 'preview-btn') markDirty()
}

/**
 * StoreProductPicker「加入绑定」→ 并入 dr.spu。**只 push / 原地占用空行，绝不重建数组**——
 * 保留手填/回填的目录外 SPU（如 990011）与 DynRowTable 的行对象身份（WeakMap key）。
 * 按 (storeId,spuId) 对既有行去重后追加，并显式 markDirty（picker 根截停了冒泡，脏值只在这里置）。
 */
function onPickerAppend(pairs: Array<{ storeId: number; spuId: number }>): void {
  const existing = dr.spu
    .filter((s) => s.spuId !== '' && s.spuId != null)
    .map((s) => ({ storeId: Number(s.storeId), spuId: Number(s.spuId) }))
  const fresh = newPairs(existing, pairs)
  if (!fresh.length) return
  for (const p of fresh) {
    const emptyIdx = dr.spu.findIndex((s) => s.spuId === '' || s.spuId == null)
    if (emptyIdx >= 0) { dr.spu[emptyIdx].storeId = p.storeId; dr.spu[emptyIdx].spuId = p.spuId }
    else dr.spu.push({ storeId: p.storeId, spuId: p.spuId })
  }
  markDirty()
}

/** manual 绑定 → 提交形状，按 (storeId,spuId) 去重（picker append 与手填自由输入可能重复；写平面不去重）。 */
function manualSpuBindings(): Array<{ storeId: number | null; spuId: number | null }> {
  const seen = new Set<string>()
  const out: Array<{ storeId: number | null; spuId: number | null }> = []
  for (const s of dr.spu) {
    if (s.spuId === '' || s.spuId == null) continue
    const storeId = numOrNull(s.storeId)
    const spuId = numOrNull(s.spuId)
    const k = storeId + '#' + spuId
    if (seen.has(k)) continue
    seen.add(k)
    out.push({ storeId, spuId })
  }
  return out
}

async function initialize(): Promise<void> {
  initialLoading.value = true
  initialErr.value = ''
  dictWarning.value = ''
  Object.assign(dr, newDraft())
  saved.value = null
  submitErr.value = ''
  submitAttempted.value = false
  showTreeErrors.value = false
  dirty.value = false
  appliedPlaybook.value = ''
  initialCtrl?.abort()
  const controller = new AbortController()
  initialCtrl = controller
  try {
    let dictionary: FieldDict | null = null
    try {
      dictionary = await dict.load()
    } catch (error) {
      // 网络断开与 HTTP 非 2xx 一样降级；编辑详情仍在后续独立加载，失败时才进入整页错误态。
      dictWarning.value = `字段配置请求失败：${(error as Error).message || '网络不可用'}`
    }
    if (controller.signal.aborted || initialCtrl !== controller) return
    if (!dictionary) {
      // apiClient 收到 401 会先清 token；不要把登录失效伪装成字段字典故障。
      if (auth.authEnabled && !auth.loggedIn) {
        await router.replace({ name: 'login', query: { returnTo: route.fullPath } })
        return
      }
      // 字典仅控制白名单下拉，不应让基础表单整页不可达。保存校验会在字典恢复前保持关闭。
      if (!dictWarning.value) dictWarning.value = '字段配置暂时不可用。你仍可填写基础信息，恢复后再配置资格条件并保存。'
    }
    if (editId.value) await loadForEdit(editId.value, controller.signal)
    else {
      // 玩法模板预填（PR-6）：模板只是**起点**，不是锁定——填完之后每一项都还能改。
      applyPlaybook(route.query.playbook as string | undefined)
      assignIds(dr.tree)
    }
  } catch (error) {
    if ((error as Error).name !== 'AbortError') initialErr.value = (error as Error).message
  } finally {
    if (initialCtrl === controller) initialLoading.value = false
  }
}

async function retryDictionary(): Promise<void> {
  if (dictRetrying.value) return
  dictRetrying.value = true
  try {
    const dictionary = await dict.load()
    if (dictionary) {
      dictWarning.value = ''
      toast.ok('字段配置已恢复')
    } else if (auth.authEnabled && !auth.loggedIn) {
      await router.replace({ name: 'login', query: { returnTo: route.fullPath } })
    } else {
      dictWarning.value = '字段配置仍不可用，请确认控制台服务地址后重试。'
    }
  } catch (error) {
    dictWarning.value = (error as Error).message || '字段配置仍不可用，请稍后重试。'
  } finally {
    dictRetrying.value = false
  }
}

watch([editId, () => route.query.playbook], () => { void initialize() }, { immediate: true })

/**
 * 应用玩法模板（PR-6）。模板只填**起点**，不锁定任何字段——填完之后每一项都还能改。
 *
 * <p>刻意不设 `dirty`：用户还没动过手，此时提示「有未保存改动」会让离开守卫误伤。
 */
function applyPlaybook(id: string | undefined): void {
  const pb = findPlaybook(id)
  if (!pb?.preset) return
  const ps = pb.preset
  dr.activityType = ps.activityType
  dr.redMode = ps.redMode
  if (ps.strategy) dr.strategy = ps.strategy
  if (ps.amount !== undefined) dr.amount = ps.amount
  // 模板自己带 N 时必须用模板的：不带过来就会沉默地用表单默认值 2，
  // 于是「第三件半价」这类模板将来配了 nth=3 也照样建出第二件半价
  if (ps.nth !== undefined) dr.nth = ps.nth
  if (ps.maxDiscount !== undefined) dr.maxDiscount = ps.maxDiscount
  if (ps.ladder) dr.ladder = ps.ladder.map((t) => ({ ...t }))
  if (ps.conditions?.length) {
    dr.tree = { logic: 'AND', children: ps.conditions.map((c) => ({ ...c })) } as GroupNode
  }
  appliedPlaybook.value = pb.name
}

async function loadForEdit(id: string, signal?: AbortSignal): Promise<void> {
  const r = await getDetail(id, signal)
  if (!r.ok) throw new Error(errText(r))
  const data = r.json as Record<string, any>
  const m = data.manage, rule = (data.rules || [])[0], cond = (data.conditions || [])[0]
  dr.activityId = id
  dr.requestId = uuid() // 编辑必须新铸 requestId，否则被幂等短路
  dr.activityType = m.activityType; dr.name = m.activityName; dr.bizLine = m.bizLine || ''
  dr.rule = m.activityRule || ''; dr.priority = m.priority; dr.inventory = m.inventory
  dr.areaType = m.activityAreaType || 1; dr.districtIds = m.districtIds || ''
  dr.startLocal = isoToLocal(m.activityStartTime); dr.endLocal = isoToLocal(m.activityEndTime)
  if (rule) {
    // 判别、解析与 submit 的逆映射都只存在于 logic.ts；页面不再维护第二份分支链。
    const { parsed: _parsed, ...benefitDraft } = benefitDraftFromRule(rule)
    Object.assign(dr, benefitDraft)
  }
  if (cond && cond.conditionTreeJson) {
    // 先剥掉写平面按「投放地域」注入的节点，再灌进条件树 UI。存储树是**合成后**的那棵，
    // 直接回读会让运营看到一条自己没写过、还含上百个代码的 userDistrictId IN(...)——
    // 而地域的权威编辑入口是上面那个 DistrictPicker，同一件事不能有两个控件。
    try {
      const stored = stripDistrictNodes(JSON.parse(cond.conditionTreeJson))
      if (stored) dr.tree = assignIds(stored) as GroupNode
    } catch { /* keep empty */ }
  }
  dr.gifts = (data.gifts || []).map((g: Record<string, unknown>) => ({ ...g }))
  const manual = (data.bindings || []).filter((b: Record<string, unknown>) => b.bindSource === 0)
  const pools = data.poolRefs || []
  if (pools.length && !manual.length) { dr.bindMode = 'pool'; dr.pool = pools.map((p: Record<string, unknown>) => ({ poolId: p.poolId })) }
  else { dr.bindMode = 'manual'; dr.spu = manual.length ? manual.map((b: Record<string, unknown>) => ({ storeId: b.storeId, spuId: b.spuId })) : [{ storeId: 1, spuId: '' }] }
  dirty.value = false
}

async function doPreview(): Promise<void> {
  showTreeErrors.value = true // 预览即暴露逐叶错误定位
  const pruned = pruneTree(dr.tree)
  if (!pruned) { previewState.value = { kind: 'ok', msg: '空条件树：所有用户恒通过' }; return }
  previewState.value = { kind: 'pending', msg: '编译中…' }
  previewCtrl?.abort()
  const controller = new AbortController()
  previewCtrl = controller
  try {
    const r = await previewTree(pruned, controller.signal)
    if (previewCtrl !== controller) return
    const j = r.json
    if (j && j.ok) previewState.value = { kind: 'ok', msg: j.message || '编译通过', drl: j.drl }
    else previewState.value = { kind: 'err', msg: (j && j.message) || errText(r) }
  } catch (error) {
    if ((error as Error).name !== 'AbortError') previewState.value = { kind: 'err', msg: (error as Error).message }
  } finally {
    if (previewCtrl === controller) previewCtrl = null
  }
}

async function submit(): Promise<void> {
  submitAttempted.value = true
  showTreeErrors.value = true
  if (!formValid.value) {
    toast.warn(`还有 ${validationErrs.value.length} 项需要补充`)
    return
  }
  submitting.value = true; submitErr.value = ''; saved.value = null
  const benefit = benefitRequestFields(dr, dr.activityType === 1)
  const body: ActivityCreateRequest = {
    requestId: dr.requestId,
    activityId: dr.activityId,
    activityName: dr.name,
    bizLine: dr.bizLine || null,
    activityType: dr.activityType,
    activityRule: dr.rule || null,
    activityStartTime: toEpoch(dr.startLocal),
    activityEndTime: toEpoch(dr.endLocal),
    activityAreaType: dr.areaType,
    districtIds: dr.areaType === 2 ? (dr.districtIds || null) : null,
    priority: numOrNull(dr.priority),
    inventory: numOrNull(dr.inventory),
    ...benefit,
    discountStrategy: dr.strategy,
    eligibilityConditionTree: pruneTree(dr.tree),
    spuBindings: dr.bindMode === 'manual' ? manualSpuBindings() : null,
    poolRefs: dr.bindMode === 'pool' ? dr.pool.filter((p) => p.poolId !== '' && p.poolId != null).map((p) => Number(p.poolId)) : null,
    // 买赠与加价购共用 activity_gift 承载，但 absoluteAmount 的含义不同：
    // 买赠 = 赠品价值，加价购 = **加多少钱换购**（决策侧 AddOnPurchaseService 读的就是它）
    gifts: dr.activityType === 5 || dr.activityType === 6 ? dr.gifts : null,
  }
  try {
    submitCtrl?.abort()
    const controller = new AbortController()
    submitCtrl = controller
    const r = await createActivity(body, controller.signal)
    if (r.ok && r.json) {
      dirty.value = false
      saved.value = { activityId: r.json.activityId, version: r.json.version, autoBoundCount: r.json.autoBoundCount, idempotentHit: r.json.idempotentHit }
      toast.ok('保存成功')
    } else {
      submitErr.value = r.status === 409
        ? '版本冲突（并发编辑），请返回列表刷新后重试。'
        : (errText(r) || '参数非法')
      if (r.status === 409) toast.warn('版本冲突')
    }
  } catch (e) {
    submitErr.value = (e as Error).message
  } finally {
    submitting.value = false
  }
}

onUnmounted(() => {
  initialCtrl?.abort()
  previewCtrl?.abort()
  submitCtrl?.abort()
  if (redModeUndoToastId !== null) toast.dismiss(redModeUndoToastId)
})

onBeforeRouteLeave(async () => {
  if (dirty.value && !saved.value) {
    return await confirm({
      title: '放弃未保存的改动？',
      body: '当前表单有未保存的编辑，离开后这些改动将丢失。',
      confirmText: '放弃并离开',
      cancelText: '继续编辑',
      danger: true,
    })
  }
  return true
})
</script>

<template>
  <section data-testid="editor-view">
    <PageHeader
      :title="editId ? '编辑活动' : '新建活动'"
      :subtitle="editId ? '调整活动配置并生成一个可复核的新版本' : '按步骤配置优惠内容、商品范围和资格条件'"
      :breadcrumb="[{ label: '控制台' }, { label: '活动列表', to: { name: 'activities' } }, { label: editId ? '编辑' : '新建' }]"
    >
      <template #actions>
        <Button variant="ghost" :to="{ name: 'activities' }"><Icon name="arrow-left" :size="15" /> 返回列表</Button>
      </template>
    </PageHeader>

    <Skeleton v-if="initialLoading" :rows="7" />
    <Banner v-else-if="initialErr" kind="err" role="alert" class="initial-error">
      <strong>无法打开活动编辑器</strong><span>{{ initialErr }}</span><button type="button" @click="initialize">重新加载</button>
    </Banner>
    <template v-else>
    <Banner v-if="dictWarning" kind="warn" role="alert" class="dict-warning" data-testid="dict-warning">
      <span><strong>字段配置未就绪</strong>{{ dictWarning }}</span>
      <button type="button" :disabled="dictRetrying" data-testid="dict-retry" @click="retryDictionary">
        {{ dictRetrying ? '重试中…' : '重新加载字段配置' }}
      </button>
    </Banner>
    <Banner v-if="appliedPlaybook" kind="info" class="dict-warning" data-testid="playbook-applied">
      <span>起点：「<strong>{{ appliedPlaybook }}</strong>」模板{{ dirty ? '（已改动）' : '' }}。下面每一项都可以改。</span>
    </Banner>
    <nav class="workflow-bar" aria-label="活动配置步骤">
      <a href="#activity-basic"><span>1</span><div><strong>基础信息</strong><small>名称 · 时间 · 地域</small></div></a>
      <Icon name="chevron-right" :size="15" />
      <a href="#activity-benefit"><span>2</span><div><strong>优惠内容</strong><small>红包 / 赠品 / 换购品</small></div></a>
      <Icon name="chevron-right" :size="15" />
      <a href="#activity-binding"><span>3</span><div><strong>圈选范围</strong><small>商品 · 资格条件</small></div></a>
      <Icon name="chevron-right" :size="15" />
      <a href="#activity-submit"><span>4</span><div><strong>校验保存</strong><small>生成待上线版本</small></div></a>
    </nav>
    <Banner v-if="editId" kind="warn"><strong>这是版本化编辑</strong>：保存后将生成下一个版本，活动回到待上线状态，需要再次复核上线。</Banner>

    <div class="layout">
      <div class="form" @input="markDirty" @click="onFormClick">
        <!-- ① 基础信息 -->
        <Section id="activity-basic" :num="1" title="活动基础信息" desc="决定活动何时生效、归属哪条业务线，以及在多活动冲突时的优先级。">
          <div class="fg">
            <label>活动名称 *<input v-model="dr.name" :placeholder="appliedPlaybook || ''" data-testid="form-name" /></label>
            <label>业务线 (bizLine)<input v-model="dr.bizLine" placeholder="如 mall" /></label>
            <label class="full">活动类型
              <Segmented
                :model-value="dr.activityType"
                :options="enabledTypes.map((t) => ({ value: t.code, label: t.label, testid: 'type-chip-' + t.code }))"
                @update:model-value="changeActivityType($event as number)"
              />
            </label>
            <label>优先级 (越小越优先)<input v-model="dr.priority" type="number" /></label>
            <label class="declarative">
              库存
              <input v-model="dr.inventory" type="number" disabled
                     title="本期为声明式：决策链路不读取、不扣减，不构成超发防护" />
              <small>声明式 · 决策不扣减</small>
            </label>
            <label>开始时间 *<input v-model="dr.startLocal" type="datetime-local" data-testid="form-start" /></label>
            <label>结束时间 *<input v-model="dr.endLocal" type="datetime-local" data-testid="form-end" /></label>
            <label>地域类型
              <select v-model.number="dr.areaType" data-testid="form-area-type">
                <option :value="1">全国</option><option :value="2">指定地域</option>
              </select>
            </label>
            <label v-if="dr.areaType === 2" class="full">投放地域 *
              <DistrictPicker v-model="districtCodes" :districts="districtStore.items"
                              :loading="districtLoading" :failed="districtStore.failed" />
            </label>
            <label class="full">活动说明 (外显)<textarea v-model="dr.rule" rows="2" /></label>
          </div>
        </Section>

        <!-- ② 红包 / ③ 买赠 -->
        <Section v-if="dr.activityType === 1" id="activity-benefit" :num="2" title="红包规则" desc="先选权益形态——它决定下面那个数字是「减多少」「几折」还是「卖多少」。">
          <!-- 六个 chip 与 BenefitForm 一一对应；takeType 只由 fixed/random 推导，不再是第二权威。 -->
          <div class="seg">
            <button type="button" class="chip" :class="{ 'chip-active': dr.redMode === 'fixed' }" :aria-pressed="dr.redMode === 'fixed'" data-testid="mode-fixed" @click="changeRedMode('fixed')">固定金额</button>
            <button type="button" class="chip" :class="{ 'chip-active': dr.redMode === 'random' }" :aria-pressed="dr.redMode === 'random'" data-testid="mode-random" @click="changeRedMode('random')">随机金额</button>
            <button type="button" class="chip" :class="{ 'chip-active': dr.redMode === 'ladder' }" :aria-pressed="dr.redMode === 'ladder'" data-testid="mode-ladder" @click="changeRedMode('ladder')">阶梯分档</button>
            <button type="button" class="chip" :class="{ 'chip-active': dr.redMode === 'ratio' }" :aria-pressed="dr.redMode === 'ratio'" data-testid="mode-ratio" @click="changeRedMode('ratio')">折扣</button>
            <button type="button" class="chip" :class="{ 'chip-active': dr.redMode === 'price' }" :aria-pressed="dr.redMode === 'price'" data-testid="mode-price" @click="changeRedMode('price')">一口价</button>
            <button type="button" class="chip" :class="{ 'chip-active': dr.redMode === 'nth' }" :aria-pressed="dr.redMode === 'nth'" data-testid="mode-nth" @click="changeRedMode('nth')">第 N 件折</button>
          </div>
          <FixedForm v-if="dr.redMode === 'fixed'" v-model:amount="dr.amount" />
          <RandomForm v-else-if="dr.redMode === 'random'" v-model:min="dr.rangeMin" v-model:max="dr.rangeMax" />
          <PriceForm v-else-if="dr.redMode === 'price'" v-model:amount="dr.amount" v-model:inventory="dr.inventory" />
          <NthForm v-else-if="dr.redMode === 'nth'" v-model:nth="dr.nth" v-model:amount="dr.amount" />
          <RatioForm v-else-if="dr.redMode === 'ratio'" v-model:amount="dr.amount" v-model:max-discount="dr.maxDiscount" :plain="ratioPlain" />
          <LadderForm v-else v-model="dr.ladder" :plain="tierPlain" />
        </Section>
        <Section v-else-if="dr.activityType === 5" id="activity-benefit" :num="2" title="买赠赠品明细" desc="配置命中活动后返回的赠品与权益。">
          <DynRowTable :rows="dr.gifts" :headers="['批次', '赠品名', '类型', '数量', '金额', '权益类型']" :make-row="() => ({ batchId: '', giftName: '', giftType: 'PHYSICAL', giftNum: 1, absoluteAmount: 0, rightType: 'GIFT' })" label="赠品" testid="gift" :min-width="600" v-slot="{ row }">
            <input v-model="(row as any).batchId" />
            <input v-model="(row as any).giftName" />
            <input v-model="(row as any).giftType" />
            <input type="number" v-model="(row as any).giftNum" />
            <input type="number" v-model="(row as any).absoluteAmount" />
            <input v-model="(row as any).rightType" />
          </DynRowTable>
        </Section>
        <!-- 加价购：与买赠同一张 activity_gift 表，但这一屏刻意不长成买赠的样子——
             那张表 6 列里有 4 列对加价购没意义，而唯一要紧的「加价金额」会混在里面。
             这里只留决策侧真正会读的两列，并把「加多少钱换购」写进表头。 -->
        <Section v-else-if="dr.activityType === 6" id="activity-benefit" :num="2" title="加价购换购品" desc="用户买了主商品后，可以从这份清单里挑一件，加指定金额换购。">
          <div class="hint">
            换购品名称是<b>第二阶段报价的匹配依据</b>，同一活动内不能重名；加价金额必须大于 0
            （0 是白送、负数是倒贴，都不是加价购，决策侧会直接跳过这一行）。
          </div>
          <DynRowTable
            :rows="dr.gifts"
            :headers="['换购品名称 *', '加价金额(元) *', '数量']"
            :make-row="() => ({ batchId: '', giftName: '', giftType: 'PHYSICAL', giftNum: 1, absoluteAmount: '', rightType: 'ADD_ON' })"
            label="换购品" testid="addon-item" :min-width="420" v-slot="{ row }"
          >
            <input v-model="(row as any).giftName" data-testid="addon-name-input" placeholder="如 品牌保温杯" />
            <input type="number" min="0.01" step="0.01" v-model="(row as any).absoluteAmount" data-testid="addon-price-input" placeholder="如 9.9" />
            <input type="number" min="1" step="1" v-model="(row as any).giftNum" />
          </DynRowTable>
        </Section>

        <!-- ④ 商品绑定 -->
        <Section id="activity-binding" :num="3" title="商品绑定" desc="手动指定 SPU，或通过商品池在保存时自动圈选。">
          <div class="seg">
            <button type="button" class="chip" :class="{ 'chip-active': dr.bindMode === 'manual' }" :aria-pressed="dr.bindMode === 'manual'" @click="dr.bindMode = 'manual'; markDirty()">手动 SPU</button>
            <button type="button" class="chip" :class="{ 'chip-active': dr.bindMode === 'pool' }" :aria-pressed="dr.bindMode === 'pool'" @click="dr.bindMode = 'pool'; markDirty()">商品池(自动圈选)</button>
          </div>
          <template v-if="dr.bindMode === 'manual'">
            <!-- 从店铺勾选商品（内联展开）；勾选结果 append 进 dr.spu，与下方手填共写同一数组 -->
            <StoreProductPicker @append="onPickerAppend" />
            <div class="hint">或直接手动输入 店铺ID / SPU ID（可绑目录外的 SPU）。</div>
            <DynRowTable :rows="dr.spu" :headers="['店铺ID', 'SPU ID']" :make-row="() => ({ storeId: 1, spuId: '' })" label="SPU 绑定" :min-width="280" v-slot="{ row }">
              <input type="number" v-model="(row as any).storeId" />
              <input type="number" v-model="(row as any).spuId" data-testid="spu-row-input" />
            </DynRowTable>
          </template>
          <template v-else>
            <div class="hint">填写商品池 ID（预置商品池为 1），保存时后端按池规则圈选并自动绑定。</div>
            <DynRowTable :rows="dr.pool" :headers="['Pool ID']" :make-row="() => ({ poolId: '' })" label="商品池" :min-width="160" v-slot="{ row }">
              <input type="number" v-model="(row as any).poolId" />
            </DynRowTable>
          </template>
        </Section>

        <!-- ⑤ 条件树 -->
        <Section :num="4" title="资格条件 (白名单条件树)" desc="空条件树 = 所有用户恒通过。字段/运算符只能从后端白名单选，服务端翻译成受控 Drools，不接受裸 DRL。">
          <ConditionGroup v-if="dictData" :node="dr.tree" :fields="dictData.fields" :operators="dictData.operators" :depth="0" :root="true" :errors="treeErrors" />
          <div v-else class="dict-placeholder">
            <Icon name="alert-triangle" :size="17" />
            <span><strong>资格条件暂不可编辑</strong><small>字段白名单恢复后会在这里显示条件构建器。</small></span>
          </div>
          <div class="preview-bar">
            <button type="button" class="mini" data-testid="preview-btn" @click="doPreview">预览条件 (试编译)</button>
            <span v-if="previewState.kind !== 'idle'" class="pv-status" :class="'pv-' + previewState.kind" data-testid="preview-status">{{ previewState.msg }}</span>
          </div>
          <div v-if="previewState.drl" class="mono-box">{{ previewState.drl }}</div>
        </Section>

        <!-- ⑥ 合并策略 -->
        <Section :num="5" title="多活动合并策略" desc="策略按 bizLine 生效；多个活动同时命中时，决定取最大优惠、累加或采用其它后端支持策略。">
          <div class="seg">
            <button v-for="strategy in strategies" :key="strategy" type="button" class="chip" :class="{ 'chip-active': dr.strategy === strategy }" :aria-pressed="dr.strategy === strategy" @click="dr.strategy = strategy; markDirty()">{{ strategy }}</button>
          </div>
        </Section>
      </div>

      <!-- 右栏：校验 & 提交 -->
      <aside id="activity-submit" class="rail">
        <div class="rail-card">
          <div class="rail-head">
            <div><span>配置完成度</span><strong>{{ completionPercent }}%</strong></div>
            <span class="readiness" :class="{ ready: formValid }"><i />{{ formValid ? '可以保存' : '待补充' }}</span>
          </div>
          <div class="progress"><i :style="{ width: completionPercent + '%' }" /></div>
          <div class="checklist">
            <div v-for="item in completionChecks" :key="item.label" :class="{ done: item.done }">
              <span><Icon :name="item.done ? 'check' : 'clock'" :size="14" /></span>{{ item.label }}
            </div>
          </div>

          <div class="draft-summary">
            <div><span>活动类型</span><strong>{{ enabledTypes.find((item) => item.code === dr.activityType)?.label || dr.activityType }}</strong></div>
            <div><span>业务线</span><strong>{{ dr.bizLine || '未填写' }}</strong></div>
            <div><span>合并策略</span><strong class="mono">{{ dr.strategy }}</strong></div>
          </div>

          <div v-if="validationErrs.length" class="validation-box" :class="{ attention: submitAttempted }" data-testid="validation-errs" role="status">
            <div class="validation-title"><Icon name="alert-triangle" :size="15" /><strong>还需补充 {{ validationErrs.length }} 项</strong></div>
            <ul><li v-for="(error, index) in validationErrs" :key="index">{{ error }}</li></ul>
          </div>
          <div v-else class="ready-box"><Icon name="badge-check" :size="17" /><span><strong>配置检查通过</strong><small>保存后仍需手动上线</small></span></div>

          <button class="primary" :disabled="submitting" data-testid="submit" type="button" @click="submit">
            <Icon :name="submitting ? 'refresh' : 'check'" :size="17" :class="{ spinning: submitting }" />
            {{ submitting ? '正在保存…' : (editId ? '保存为新版本' : '保存活动') }}
          </button>
          <p class="save-note"><Icon name="info" :size="13" /> 保存不会自动上线，可先在详情页复核。</p>
        </div>

        <Card v-if="saved" title="活动已保存" data-testid="save-success">
          <Kv k="活动ID" mono>{{ saved.activityId }}</Kv>
          <Kv k="版本">v{{ saved.version }}</Kv>
          <Kv k="自动圈选绑定">{{ saved.autoBoundCount }} 个</Kv>
          <div v-if="saved.idempotentHit" class="tag-gold" data-testid="idempotent-hit">幂等命中：重复提交返回首次结果</div>
          <Button variant="subtle" size="sm" class="back" @click="router.push({ name: 'activities' })">
            <Icon name="arrow-left" :size="15" /><span>返回列表</span>
          </Button>
        </Card>
        <Banner v-if="submitErr" kind="err" role="alert" data-testid="conflict-hint">{{ submitErr }}</Banner>
      </aside>
    </div>
    </template>
  </section>
</template>

<style scoped>
.form :deep(.plain-preview) {
  margin: var(--gap-inline) 0 0; padding: var(--sp-3) var(--sp-4);
  background: var(--bg-soft); border-left: 3px solid var(--accent-line);
  font-size: var(--fs-lg); line-height: var(--lh-relaxed); color: var(--text);
}
.form :deep(.raw-tiers) { margin-top: var(--gap-group); }
.form :deep(.raw-tiers summary) { font-size: var(--fs-sm); color: var(--text-faint); cursor: pointer; }

/* 声明式字段：配置存得下但当前实现不执行。置灰 + 明示，避免运营以为配了就生效（DECISION_RECORD D12-3）。 */
.declarative input[disabled] { background: var(--bg-soft); color: var(--text-faint); cursor: not-allowed; }
.declarative small { display: block; margin-top: 2px; font-size: 11px; color: var(--warn); }
.initial-error { display: flex; flex-direction: column; align-items: flex-start; gap: var(--sp-1); padding: var(--sp-4); }.initial-error span { font-size: var(--fs-xs); }.initial-error button { margin-top: var(--sp-1); padding: var(--sp-1) var(--sp-3); border: 1px solid currentColor; border-radius: var(--radius-sm); background: transparent; color: inherit; cursor: pointer; }
.dict-warning { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); margin-bottom: var(--sp-4); }.dict-warning span, .dict-warning strong { display: block; }.dict-warning strong { margin-bottom: 2px; }.dict-warning button { flex: 0 0 auto; padding: var(--sp-1) var(--sp-3); border: 1px solid currentColor; border-radius: var(--radius-sm); background: transparent; color: inherit; cursor: pointer; }.dict-warning button:disabled { opacity: .55; cursor: wait; }
.workflow-bar { display: grid; grid-template-columns: 1fr auto 1fr auto 1fr auto 1fr; align-items: center; gap: var(--sp-2); margin-bottom: var(--sp-4); padding: var(--sp-3) var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); box-shadow: var(--shadow-sm); }
.workflow-bar > a { display: flex; align-items: center; gap: var(--sp-2); min-width: 0; padding: var(--sp-2); border-radius: var(--radius-sm); color: var(--text); text-decoration: none; }.workflow-bar > a:hover { background: var(--bg-hover); }.workflow-bar > a > span { display: inline-flex; align-items: center; justify-content: center; flex: 0 0 auto; width: 28px; height: 28px; border-radius: 9px; background: var(--accent-soft); color: var(--accent); font-size: 11px; font-weight: var(--fw-bold); font-variant-numeric: tabular-nums; }.workflow-bar strong, .workflow-bar small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.workflow-bar strong { font-size: 11px; }.workflow-bar small { color: var(--text-faint); font-size: var(--fs-2xs); }.workflow-bar > :deep(svg) { color: var(--text-faint); }
.layout { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: var(--sp-5); align-items: start; }
.form :deep(.section) { scroll-margin-top: var(--sp-4); }
.form :deep(.fg) { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); }
/* ⚠️ 这几条必须用 `>` 直接子代，不能写成后代选择器。
   `:deep()` 里的后代部分**不带 scope 属性**，所以 `.fg label` 会命中**任何子组件内部**的 label：
   `.form[data-v-x] .fg label` 特指度 (0,3,1)，压过子组件自己的 `.row[data-v-y]` (0,2,0)。
   实测后果（旧 DistrictCascader；现 DistrictTree 同理）：每一行的 checkbox / 地名 / 展开三角被强制
   `flex-direction: column` 竖成三行、行高 104px，34 个省的列表只看得见两三项；同理 `.fg input` 把树里的
   checkbox 撑成 38px 高的输入框、还给搜索框套了第二层边框。
   本 `.fg` 里只有表单自己的字段 label 与 Segmented / DistrictPicker 两个组件，
   直接子代选择器覆盖前者、且不再泄漏进后者。 */
.form :deep(.fg > label) { display: flex; flex-direction: column; gap: var(--sp-1); font-size: var(--fs-xs); color: var(--text-soft); font-weight: var(--fw-medium); }
.form :deep(.fg .full) { grid-column: 1 / -1; }
.form :deep(.fg > label > input), .form :deep(.fg > label > select), .form :deep(.fg > label > textarea) { min-height: 38px; padding: var(--sp-2) var(--sp-3); border: 1px solid var(--border-ctl); border-radius: var(--radius-sm); background: var(--bg-soft); color: var(--text); font-family: inherit; transition: border-color var(--dur-fast) var(--ease-out), background var(--dur-fast) var(--ease-out), box-shadow var(--dur-fast) var(--ease-out); }.form :deep(.fg > label > textarea) { min-height: 68px; resize: vertical; }.form :deep(.fg > label > input:hover), .form :deep(.fg > label > select:hover), .form :deep(.fg > label > textarea:hover) { border-color: var(--border-strong); }.form :deep(.fg > label > input:focus), .form :deep(.fg > label > select:focus), .form :deep(.fg > label > textarea:focus) { outline: 0; border-color: var(--accent); background: var(--bg-elev); box-shadow: var(--focus-ring); }
.seg { display: flex; gap: var(--sp-1); flex-wrap: wrap; margin: 0 0 var(--sp-2); }
.chip { padding: var(--sp-1) var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-pill); background: var(--bg-elev); color: var(--text); font-size: 12.5px; cursor: pointer; transition: background var(--dur-fast) var(--ease-out); }
.chip:hover { background: var(--bg-hover); }
.chip-active, .chip-active:hover { background: var(--accent); color: var(--text-invert); border-color: var(--accent); }
.hint { font-size: 12px; color: var(--text-faint); margin: var(--sp-1) 0; }
.dict-placeholder { display: flex; align-items: center; gap: var(--sp-2); min-height: 74px; padding: var(--sp-3); border: 1px dashed var(--border-strong); border-radius: var(--radius-sm); background: var(--bg-soft); color: var(--gold); }.dict-placeholder span, .dict-placeholder strong, .dict-placeholder small { display: block; }.dict-placeholder strong { color: var(--text); font-size: var(--fs-sm); }.dict-placeholder small { margin-top: 2px; color: var(--text-faint); }
.preview-bar { display: flex; align-items: center; gap: var(--sp-2); margin: var(--sp-3) 0 var(--sp-1); padding-top: var(--sp-3); border-top: 1px solid var(--border); }
.mini { padding: var(--sp-1) var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); cursor: pointer; font-size: 12px; }
.pv-status { font-size: 12px; }
.pv-ok { color: var(--green); } .pv-err { color: var(--err); } .pv-pending { color: var(--text-soft); }
.mono-box { font-family: var(--mono); font-size: 12px; background: var(--bg-soft); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: var(--sp-2); white-space: pre-wrap; word-break: break-all; margin: var(--sp-2) 0; }
/* 粘住点必须让开顶栏（56px）+ 一档留白，否则 rail 顶部会被 sticky 顶栏遮掉约 40px。
   与 F5 同源的漏网：全站 sticky 元素的 top 都要以 --shell-topbar-h 为基准。 */
.rail { align-self: start; position: sticky; top: calc(var(--shell-topbar-h) + var(--sp-4)); scroll-margin-top: calc(var(--shell-topbar-h) + var(--sp-4)); }
.rail-card { overflow: hidden; padding: var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); box-shadow: var(--shadow-md); }
.rail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--sp-3); }.rail-head > div > span, .rail-head > div > strong { display: block; }.rail-head > div > span { color: var(--text-soft); font-size: var(--fs-xs); }.rail-head > div > strong { margin-top: 2px; font-size: 25px; font-variant-numeric: tabular-nums; line-height: 1; }.readiness { display: inline-flex; align-items: center; gap: 5px; padding: 4px 7px; border-radius: var(--radius-pill); background: var(--gold-soft); color: var(--gold); font-size: var(--fs-2xs); font-weight: var(--fw-semibold); }.readiness i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }.readiness.ready { background: var(--green-soft); color: var(--green); }
.progress { overflow: hidden; height: 5px; margin: var(--sp-3) 0; border-radius: var(--radius-pill); background: var(--bg-soft); }.progress i { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, var(--accent), var(--accent-2)); transition: width var(--dur-mid) var(--ease-out); }
.checklist { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-2); padding-bottom: var(--sp-3); border-bottom: 1px solid var(--border); }.checklist > div { display: flex; align-items: center; gap: var(--sp-1); color: var(--text-faint); font-size: var(--fs-xs); }.checklist > div > span { display: inline-flex; color: var(--text-faint); }.checklist > div.done { color: var(--text); }.checklist > div.done > span { color: var(--green); }
.draft-summary { display: flex; flex-direction: column; gap: var(--sp-2); padding: var(--sp-3) 0; }.draft-summary > div { display: flex; justify-content: space-between; gap: var(--sp-3); font-size: var(--fs-xs); }.draft-summary span { color: var(--text-faint); }.draft-summary strong { overflow: hidden; max-width: 60%; text-overflow: ellipsis; white-space: nowrap; }
.validation-box { padding: var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-soft); }.validation-box.attention { border-color: var(--gold); background: var(--gold-soft); }.validation-title { display: flex; align-items: center; gap: var(--sp-2); color: var(--gold); font-size: 11px; }.validation-box ul { margin: var(--sp-2) 0 0; padding-left: 18px; color: var(--text-soft); font-size: var(--fs-xs); line-height: 1.7; }.ready-box { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-3); border: 1px solid color-mix(in srgb, var(--green) 25%, var(--border)); border-radius: var(--radius-sm); background: var(--green-soft); color: var(--green); }.ready-box span, .ready-box strong, .ready-box small { display: block; }.ready-box strong { font-size: 11px; }.ready-box small { margin-top: 1px; font-size: var(--fs-2xs); opacity: .82; }
.primary { width: 100%; display: inline-flex; align-items: center; justify-content: center; gap: var(--sp-2); min-height: 46px; margin-top: var(--sp-3); background: linear-gradient(100deg, var(--accent), var(--accent-2)); color: var(--text-invert); border: none; border-radius: var(--radius-sm); padding: var(--sp-3); cursor: pointer; font-size: 13px; font-weight: var(--fw-semibold); box-shadow: 0 8px 18px color-mix(in srgb, var(--accent) 20%, transparent); transition: background var(--dur-fast) var(--ease-out), transform var(--dur-fast) var(--ease-out); }
.primary:hover:not(:disabled) { background: var(--accent-hover); }
.primary:disabled { opacity: .5; cursor: not-allowed; }
.spinning { animation: spin .9s linear infinite; }.save-note { display: flex; align-items: center; justify-content: center; gap: var(--sp-1); margin: var(--sp-2) 0 0; color: var(--text-faint); font-size: var(--fs-2xs); }
.tag-gold { background: var(--gold-soft); color: var(--gold); font-size: 12px; padding: var(--sp-1) var(--sp-2); border-radius: var(--radius-sm); margin-top: var(--sp-2); }
.back { margin-top: var(--sp-3); width: 100%; }
@media (max-width: 1023px) { .workflow-bar { grid-template-columns: 1fr 1fr; }.workflow-bar > :deep(svg) { display: none; }.layout { grid-template-columns: 1fr; } .rail { position: static; } .form :deep(.fg) { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .dict-warning { align-items: flex-start; flex-direction: column; }.workflow-bar { grid-template-columns: 1fr 1fr; padding: var(--sp-2); }.workflow-bar small { display: none; }.workflow-bar > a > span { width: 24px; height: 24px; }.checklist { grid-template-columns: 1fr; } }
</style>
