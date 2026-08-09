<script setup lang="ts">
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { createActivity, getDetail, previewTree } from '../activityApi'
import { useDictStore } from '@/stores/useDictStore'
import { useAuthStore } from '@/auth/useAuthStore'
import { useToast } from '@/shared/useToast'
import { useConfirm } from '@/shared/useConfirm'
import { errText } from '@/shared/apiClient'
import {
  uuid, numOrNull, toEpoch, toLocalInput, isoToLocal, cleanLadder, parseLadder,
  pruneTree, assignIds, emptyGroup, validateTree, invalidLeafReasons, type LadderRow,
} from '../logic'
import type { ActivityCreateRequest, FieldDict, GroupNode } from '@/shared/types'
import ConditionGroup from '../condition-tree/ConditionGroup.vue'
import DynRowTable from '../DynRowTable.vue'
import TierRuler from '../benefit/TierRuler.vue'
import { normalizeTiers, plainLanguage } from '../benefit/tierLogic'
import { findPlaybook } from '../playbooks'
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
const auth = useAuthStore()
const toast = useToast()
const { confirm } = useConfirm()
const editId = computed(() => (route.name === 'activity-edit' ? (route.params.id as string) : null))

interface Draft {
  activityId: string | null
  requestId: string
  activityType: number
  redMode: 'fixed' | 'ladder' | 'ratio'
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
  takeType: number
  unit: string
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
    districtIds: '', amount: '', maxDiscount: '', takeType: 1, unit: '元', strategy: 'MAX',
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
/** 本次表单是从哪个玩法模板起步的（PR-6）。只作提示，不影响提交内容 */
const appliedPlaybook = ref('')
const submitAttempted = ref(false)
const previewState = ref<{ kind: 'idle' | 'pending' | 'ok' | 'err'; msg: string; drl?: string }>({ kind: 'idle', msg: '' })
let initialCtrl: AbortController | null = null
let previewCtrl: AbortController | null = null
let submitCtrl: AbortController | null = null
// 条件树逐叶行内错误：预览/提交尝试后才显（避免打字中闪红），之后随修复实时收敛。
const showTreeErrors = ref(false)
const treeErrors = computed(() =>
  showTreeErrors.value ? invalidLeafReasons(dr.tree, dictData.value?.operators || []) : undefined,
)

const dictData = computed(() => dict.cache['__default__'] || null)
const enabledTypes = computed(() => (dictData.value?.activityTypes || []).filter((t) => t.code === 1 || t.code === 5))
const strategies = computed(() => dictData.value?.strategies || [])
const distModes = computed(() => dictData.value?.distributionModes || [])

// 就地校验
const validationErrs = computed(() => {
  const errs: string[] = []
  if (!dictData.value) errs.push('字段配置未加载，暂时不能保存')
  if (!dr.name.trim()) errs.push('活动名称必填')
  if (!dr.startLocal) errs.push('开始时间必填')
  if (!dr.endLocal) errs.push('结束时间必填')
  if (dr.startLocal && dr.endLocal && toEpoch(dr.startLocal)! >= toEpoch(dr.endLocal)!) errs.push('开始时间须早于结束时间')
  if (dr.activityType === 1 && dr.redMode === 'fixed' && (dr.amount === '' || dr.amount == null)) errs.push('固定红包金额必填')
  if (dr.activityType === 1 && dr.redMode === 'ladder' && !cleanLadder(dr.ladder).length) errs.push('阶梯档至少一档有奖励')
  if (dr.activityType === 1 && dr.redMode === 'ratio') {
    const zhe = Number(dr.amount)
    if (dr.amount === '' || dr.amount == null || Number.isNaN(zhe)) errs.push('折数必填')
    else if (zhe <= 0 || zhe >= 10) errs.push('折数须在 0 与 10 之间（8 = 八折；10 折=不打折、0 折=白送）')
    // 封顶是硬要求不是建议：打 8 折在一笔 10 万的订单上就是 2 万
    const cap = Number(dr.maxDiscount)
    if (dr.maxDiscount === '' || dr.maxDiscount == null || Number.isNaN(cap) || cap <= 0) errs.push('折扣券必须填封顶减免额（不封顶等于无上限支出）')
  }
  const treeErrs = validateTree(pruneTree(dr.tree), dictData.value?.operators || [])
  if (treeErrs.length) errs.push('条件树有 ' + treeErrs.length + ' 处未填完整')
  return errs
})
const formValid = computed(() => validationErrs.value.length === 0)
const completionChecks = computed(() => [
  { label: '基础信息', done: !!dr.name.trim() && !!dr.startLocal && !!dr.endLocal },
  { label: dr.activityType === 1 ? '红包规则' : '赠品明细', done: dr.activityType === 1
      ? (dr.redMode === 'ladder' ? cleanLadder(dr.ladder).length > 0
        : dr.redMode === 'ratio' ? dr.amount !== '' && dr.maxDiscount !== ''
        : dr.amount !== '')
      : dr.gifts.length > 0 },
  { label: '商品绑定', done: dr.bindMode === 'manual' ? dr.spu.some((item) => item.spuId !== '') : dr.pool.some((item) => item.poolId !== '') },
  { label: '资格条件', done: validateTree(pruneTree(dr.tree), dictData.value?.operators || []).length === 0 },
])
const completionPercent = computed(() => Math.round(completionChecks.value.filter((item) => item.done).length / completionChecks.value.length * 100))

function markDirty(): void {
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

watch(editId, () => { void initialize() }, { immediate: true })

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
  if (ps.maxDiscount !== undefined) dr.maxDiscount = ps.maxDiscount
  if (ps.ladder) dr.ladder = ps.ladder.map((t) => ({ ...t }))
  if (ps.conditions?.length) {
    dr.tree = { logic: 'AND', children: ps.conditions.map((c) => ({ ...c })) } as GroupNode
  }
  if (!dr.name) dr.name = pb.name
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
    dr.takeType = rule.redPackageTakeType || 1; dr.unit = rule.redPackageAmountUnit || '元'
    if (rule.redPackageRangeAmount) { dr.redMode = 'ladder'; dr.ladder = parseLadder(rule.redPackageRangeAmount) }
    else if (dr.unit === '折') {
      // 折扣型：amount 是折数，还要把封顶带回来——不带回来的话，一次「改个错别字」的编辑
      // 就会提交一个没有封顶的折扣券，被写平面拒成 400，而运营完全不知道少了什么
      dr.redMode = 'ratio'
      dr.amount = rule.redPackageAmount ?? ''
      dr.maxDiscount = rule.redPackageMaxDiscount ?? ''
    }
    else { dr.redMode = 'fixed'; dr.amount = rule.redPackageAmount ?? '' }
  }
  if (cond && cond.conditionTreeJson) {
    try { dr.tree = assignIds(JSON.parse(cond.conditionTreeJson)) as GroupNode } catch { /* keep empty */ }
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
    redPackageTakeType: dr.activityType === 1 && dr.redMode === 'fixed' ? dr.takeType : null,
    // 折扣型把「折数」放在 redPackageAmount 里，靠 unit 判别（与后端 BenefitForm 一致）
    redPackageAmount: dr.activityType === 1 && dr.redMode !== 'ladder' ? numOrNull(dr.amount) : null,
    redPackageAmountUnit: dr.activityType === 1 && dr.redMode === 'ratio' ? '折' : '元',
    redPackageMaxDiscount: dr.activityType === 1 && dr.redMode === 'ratio' ? numOrNull(dr.maxDiscount) : null,
    redPackageRangeAmount: dr.activityType === 1 && dr.redMode === 'ladder' ? JSON.stringify(cleanLadder(dr.ladder)) : null,
    discountStrategy: dr.strategy,
    eligibilityConditionTree: pruneTree(dr.tree),
    spuBindings: dr.bindMode === 'manual'
      ? dr.spu.filter((s) => s.spuId !== '' && s.spuId != null).map((s) => ({ storeId: numOrNull(s.storeId), spuId: numOrNull(s.spuId) }))
      : null,
    poolRefs: dr.bindMode === 'pool' ? dr.pool.filter((p) => p.poolId !== '' && p.poolId != null).map((p) => Number(p.poolId)) : null,
    gifts: dr.activityType === 5 ? dr.gifts : null,
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
      <span>已按「<strong>{{ appliedPlaybook }}</strong>」模板预填。模板只是起点——下面每一项都可以改。</span>
    </Banner>
    <nav class="workflow-bar" aria-label="活动配置步骤">
      <a href="#activity-basic"><span>1</span><div><strong>基础信息</strong><small>名称 · 时间 · 地域</small></div></a>
      <Icon name="chevron-right" :size="15" />
      <a href="#activity-benefit"><span>2</span><div><strong>优惠内容</strong><small>红包或赠品</small></div></a>
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
            <label>活动名称 *<input v-model="dr.name" data-testid="form-name" /></label>
            <label>业务线 (bizLine)<input v-model="dr.bizLine" placeholder="如 mall" /></label>
            <label class="full">活动类型
              <Segmented
                :model-value="dr.activityType"
                :options="enabledTypes.map((t) => ({ value: t.code, label: t.label, testid: 'type-chip-' + t.code }))"
                @update:model-value="dr.activityType = ($event as number); markDirty()"
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
              <select v-model.number="dr.areaType"><option :value="1">全国</option><option :value="2">指定地域</option></select>
            </label>
            <label v-if="dr.areaType === 2">地域IDs (逗号)<input v-model="dr.districtIds" /></label>
            <label class="full">活动说明 (外显)<textarea v-model="dr.rule" rows="2" /></label>
          </div>
        </Section>

        <!-- ② 红包 / ③ 买赠 -->
        <Section v-if="dr.activityType === 1" id="activity-benefit" :num="2" title="红包规则" desc="选择固定金额或按订单金额配置阶梯奖励。">
          <div class="seg">
            <button type="button" class="chip" :class="{ 'chip-active': dr.redMode === 'fixed' }" :aria-pressed="dr.redMode === 'fixed'" @click="dr.redMode = 'fixed'; markDirty()">固定金额</button>
            <button type="button" class="chip" :class="{ 'chip-active': dr.redMode === 'ladder' }" :aria-pressed="dr.redMode === 'ladder'" @click="dr.redMode = 'ladder'; markDirty()">阶梯分档</button>
            <button type="button" class="chip" :class="{ 'chip-active': dr.redMode === 'ratio' }" :aria-pressed="dr.redMode === 'ratio'" data-testid="mode-ratio" @click="dr.redMode = 'ratio'; markDirty()">折扣</button>
          </div>
          <div v-if="dr.redMode === 'fixed'" class="fg">
            <label>红包金额<input v-model="dr.amount" type="number" placeholder="0 ~ 999999" data-testid="form-amount" /></label>
            <label>发放方式
              <!-- 「随机金额」(code 2) 置灰：redPackageTakeType 全链路只被搬运，
                   BenefitEvaluator.computeAmounts 直接把 redPackageAmount 抄给 computedAmount，
                   从不看它——配成随机，线上照样发固定值。与 inventory 同族的声明式字段，
                   按库存红线 B 的先例「置灰 + 明示」，而不是留着让运营配了以为生效。 -->
              <select v-model.number="dr.takeType" data-testid="form-take-type">
                <option v-for="m in distModes" :key="m.code" :value="m.code" :disabled="m.code !== 1">
                  {{ m.label }}{{ m.code === 1 ? '' : '（未实现）' }}
                </option>
              </select>
              <small>随机金额尚未实现：决策链路不读取该字段，配了仍按固定金额发</small>
            </label>
          </div>
          <div v-else-if="dr.redMode === 'ratio'" class="fg">
            <label>折数 *
              <input v-model="dr.amount" type="number" step="0.1" min="0.1" max="9.9"
                     placeholder="8 = 八折" data-testid="form-zhe" />
              <small>8 = 八折（按原价 80% 收，减免 20%）</small>
            </label>
            <label>封顶减免额 (元) *
              <input v-model="dr.maxDiscount" type="number" min="0.01"
                     placeholder="例如 50" data-testid="form-max-discount" />
              <!-- 封顶不是可选项：不封顶的折扣券在大额订单上就是无上限支出 -->
              <small>必填。打 8 折在一笔 10 万的订单上就是减 2 万</small>
            </label>
            <p class="plain-preview full" data-testid="ratio-plain">{{ ratioPlain }}</p>
          </div>
          <template v-else>
            <!-- PR-4：档位改用刻度尺。顺序 / 间距 / 覆盖是否连续，在尺上一眼可见，不用在 N 行输入框里心算 -->
            <TierRuler v-model="dr.ladder" />
            <!-- 人话预览：本屏成败的分水岭。运营填完必须知道自己配出了什么，否则不敢按发布 -->
            <p class="plain-preview" data-testid="tier-plain">{{ tierPlain }}</p>
            <details class="raw-tiers">
              <summary>精确编辑（起 / 止 / 奖励）</summary>
          <DynRowTable :rows="dr.ladder" :headers="['起(min)', '止(max,空=无上限)', '奖励(reward)']" :make-row="() => ({ min: '', max: '', reward: '' })" label="阶梯档" v-slot="{ row }">
            <input type="number" v-model="(row as LadderRow).min" />
            <input type="number" v-model="(row as LadderRow).max" />
            <input type="number" v-model="(row as LadderRow).reward" />
          </DynRowTable>
            </details>
          </template>
        </Section>
        <Section v-else-if="dr.activityType === 5" id="activity-benefit" :num="2" title="买赠赠品明细" desc="配置命中活动后返回的赠品与权益。">
          <DynRowTable :rows="dr.gifts" :headers="['批次', '赠品名', '类型', '数量', '金额', '权益类型']" :make-row="() => ({ batchId: '', giftName: '', giftType: 'PHYSICAL', giftNum: 1, absoluteAmount: 0, rightType: 'GIFT' })" label="赠品" :min-width="600" v-slot="{ row }">
            <input v-model="(row as any).batchId" />
            <input v-model="(row as any).giftName" />
            <input v-model="(row as any).giftType" />
            <input type="number" v-model="(row as any).giftNum" />
            <input type="number" v-model="(row as any).absoluteAmount" />
            <input v-model="(row as any).rightType" />
          </DynRowTable>
        </Section>

        <!-- ④ 商品绑定 -->
        <Section id="activity-binding" :num="3" title="商品绑定" desc="手动指定 SPU，或通过商品池在保存时自动圈选。">
          <div class="seg">
            <button type="button" class="chip" :class="{ 'chip-active': dr.bindMode === 'manual' }" :aria-pressed="dr.bindMode === 'manual'" @click="dr.bindMode = 'manual'; markDirty()">手动 SPU</button>
            <button type="button" class="chip" :class="{ 'chip-active': dr.bindMode === 'pool' }" :aria-pressed="dr.bindMode === 'pool'" @click="dr.bindMode = 'pool'; markDirty()">商品池(自动圈选)</button>
          </div>
          <DynRowTable v-if="dr.bindMode === 'manual'" :rows="dr.spu" :headers="['店铺ID', 'SPU ID']" :make-row="() => ({ storeId: 1, spuId: '' })" label="SPU 绑定" :min-width="280" v-slot="{ row }">
            <input type="number" v-model="(row as any).storeId" />
            <input type="number" v-model="(row as any).spuId" data-testid="spu-row-input" />
          </DynRowTable>
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
.plain-preview {
  margin: var(--gap-inline) 0 0; padding: var(--sp-3) var(--sp-4);
  background: var(--bg-soft); border-left: 3px solid var(--accent-line);
  font-size: var(--fs-lg); line-height: var(--lh-relaxed); color: var(--text);
}
.raw-tiers { margin-top: var(--gap-group); }
.raw-tiers summary { font-size: var(--fs-sm); color: var(--text-faint); cursor: pointer; }

/* 声明式字段：配置存得下但当前实现不执行。置灰 + 明示，避免运营以为配了就生效（DECISION_RECORD D12-3）。 */
.declarative input[disabled] { background: var(--bg-soft); color: var(--text-faint); cursor: not-allowed; }
.declarative small { display: block; margin-top: 2px; font-size: 11px; color: var(--warn); }
.initial-error { display: flex; flex-direction: column; align-items: flex-start; gap: var(--sp-1); padding: var(--sp-4); }.initial-error span { font-size: var(--fs-xs); }.initial-error button { margin-top: var(--sp-1); padding: var(--sp-1) var(--sp-3); border: 1px solid currentColor; border-radius: var(--radius-sm); background: transparent; color: inherit; cursor: pointer; }
.dict-warning { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); margin-bottom: var(--sp-4); }.dict-warning span, .dict-warning strong { display: block; }.dict-warning strong { margin-bottom: 2px; }.dict-warning button { flex: 0 0 auto; padding: var(--sp-1) var(--sp-3); border: 1px solid currentColor; border-radius: var(--radius-sm); background: transparent; color: inherit; cursor: pointer; }.dict-warning button:disabled { opacity: .55; cursor: wait; }
.workflow-bar { display: grid; grid-template-columns: 1fr auto 1fr auto 1fr auto 1fr; align-items: center; gap: var(--sp-2); margin-bottom: var(--sp-4); padding: var(--sp-3) var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); box-shadow: var(--shadow-sm); }
.workflow-bar > a { display: flex; align-items: center; gap: var(--sp-2); min-width: 0; padding: var(--sp-2); border-radius: var(--radius-sm); color: var(--text); text-decoration: none; }.workflow-bar > a:hover { background: var(--bg-hover); }.workflow-bar > a > span { display: inline-flex; align-items: center; justify-content: center; flex: 0 0 auto; width: 28px; height: 28px; border-radius: 9px; background: var(--accent-soft); color: var(--accent); font-size: 11px; font-weight: var(--fw-bold); font-variant-numeric: tabular-nums; }.workflow-bar strong, .workflow-bar small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.workflow-bar strong { font-size: 11px; }.workflow-bar small { color: var(--text-faint); font-size: var(--fs-2xs); }.workflow-bar > :deep(svg) { color: var(--text-faint); }
.layout { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: var(--sp-5); align-items: start; }
.form :deep(.section) { scroll-margin-top: var(--sp-4); }
.fg { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); }
.fg label { display: flex; flex-direction: column; gap: var(--sp-1); font-size: var(--fs-xs); color: var(--text-soft); font-weight: var(--fw-medium); }
.fg .full { grid-column: 1 / -1; }
.fg input, .fg select, .fg textarea { min-height: 38px; padding: var(--sp-2) var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-soft); color: var(--text); font-family: inherit; transition: border-color var(--dur-fast) var(--ease-out), background var(--dur-fast) var(--ease-out), box-shadow var(--dur-fast) var(--ease-out); }.fg textarea { min-height: 68px; resize: vertical; }.fg input:hover, .fg select:hover, .fg textarea:hover { border-color: var(--border-strong); }.fg input:focus, .fg select:focus, .fg textarea:focus { outline: 0; border-color: var(--accent); background: var(--bg-elev); box-shadow: var(--focus-ring); }
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
@media (max-width: 1023px) { .workflow-bar { grid-template-columns: 1fr 1fr; }.workflow-bar > :deep(svg) { display: none; }.layout { grid-template-columns: 1fr; } .rail { position: static; } .fg { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .dict-warning { align-items: flex-start; flex-direction: column; }.workflow-bar { grid-template-columns: 1fr 1fr; padding: var(--sp-2); }.workflow-bar small { display: none; }.workflow-bar > a > span { width: 24px; height: 24px; }.checklist { grid-template-columns: 1fr; } }
</style>
