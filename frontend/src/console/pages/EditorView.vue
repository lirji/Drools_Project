<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { createActivity, getDetail, previewTree } from '../activityApi'
import { useDictStore } from '@/stores/useDictStore'
import { useToast } from '@/shared/useToast'
import { useConfirm } from '@/shared/useConfirm'
import { errText } from '@/shared/apiClient'
import {
  uuid, numOrNull, toEpoch, toLocalInput, isoToLocal, cleanLadder, parseLadder,
  pruneTree, assignIds, emptyGroup, validateTree, invalidLeafReasons, type LadderRow,
} from '../logic'
import type { ActivityCreateRequest, GroupNode } from '@/shared/types'
import ConditionGroup from '../condition-tree/ConditionGroup.vue'
import DynRowTable from '../DynRowTable.vue'
import Card from '@/shared/ui/Card.vue'
import Kv from '@/shared/ui/Kv.vue'
import Banner from '@/shared/ui/Banner.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import Segmented from '@/shared/ui/Segmented.vue'
import Section from '@/shared/ui/Section.vue'
import Button from '@/shared/ui/Button.vue'
import Icon from '@/shared/ui/Icon.vue'

const route = useRoute()
const router = useRouter()
const dict = useDictStore()
const toast = useToast()
const { confirm } = useConfirm()
const editId = computed(() => (route.name === 'activity-edit' ? (route.params.id as string) : null))

interface Draft {
  activityId: string | null
  requestId: string
  activityType: number
  redMode: 'fixed' | 'ladder'
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
    districtIds: '', amount: '', takeType: 1, unit: '元', strategy: 'MAX',
    ladder: [], gifts: [], spu: [{ storeId: 1, spuId: '' }], pool: [{ poolId: '' }],
    tree: emptyGroup(),
  }
}

const dr = reactive<Draft>(newDraft())
const submitting = ref(false)
const submitErr = ref('')
const saved = ref<{ activityId: string; version: number; autoBoundCount: number; idempotentHit: boolean } | null>(null)
const dirty = ref(false)
const previewState = ref<{ kind: 'idle' | 'pending' | 'ok' | 'err'; msg: string; drl?: string }>({ kind: 'idle', msg: '' })
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
  if (!dr.name.trim()) errs.push('活动名称必填')
  if (!dr.startLocal) errs.push('开始时间必填')
  if (!dr.endLocal) errs.push('结束时间必填')
  if (dr.startLocal && dr.endLocal && toEpoch(dr.startLocal)! >= toEpoch(dr.endLocal)!) errs.push('开始时间须早于结束时间')
  if (dr.activityType === 1 && dr.redMode === 'fixed' && (dr.amount === '' || dr.amount == null)) errs.push('固定红包金额必填')
  if (dr.activityType === 1 && dr.redMode === 'ladder' && !cleanLadder(dr.ladder).length) errs.push('阶梯档至少一档有奖励')
  const treeErrs = validateTree(pruneTree(dr.tree), dictData.value?.operators || [])
  if (treeErrs.length) errs.push('条件树有 ' + treeErrs.length + ' 处未填完整')
  return errs
})
const canSubmit = computed(() => validationErrs.value.length === 0 && !submitting.value)

onMounted(async () => {
  await dict.load()
  if (editId.value) await loadForEdit(editId.value)
  else assignIds(dr.tree)
})

async function loadForEdit(id: string): Promise<void> {
  const r = await getDetail(id)
  if (!r.ok) { toast.err(errText(r)); return }
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
  const r = await previewTree(pruned)
  const j = r.json
  if (j && j.ok) previewState.value = { kind: 'ok', msg: j.message || '编译通过', drl: j.drl }
  else previewState.value = { kind: 'err', msg: (j && j.message) || errText(r) }
}

async function submit(): Promise<void> {
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
    redPackageAmount: dr.activityType === 1 && dr.redMode === 'fixed' ? numOrNull(dr.amount) : null,
    redPackageAmountUnit: dr.unit,
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
    const r = await createActivity(body)
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
  <section data-testid="editor-view" @input="dirty = true">
    <PageHeader
      :title="editId ? '编辑活动' : '新建活动'"
      subtitle="报表式配置 → 白名单条件树 → 保存后上线"
      :breadcrumb="[{ label: '控制台' }, { label: '活动列表', to: { name: 'activities' } }, { label: editId ? '编辑' : '新建' }]"
    />
    <Banner v-if="editId" kind="warn">编辑将生成新版本 (version+1)，且活动状态回到「待上线」，保存后需重新上线。</Banner>

    <div class="layout">
      <div class="form">
        <!-- ① 基础信息 -->
        <Section :num="1" title="活动基础信息">
          <div class="fg">
            <label>活动名称 *<input v-model="dr.name" data-testid="form-name" /></label>
            <label>业务线 (bizLine)<input v-model="dr.bizLine" placeholder="如 mall" /></label>
            <label class="full">活动类型
              <Segmented
                :model-value="dr.activityType"
                :options="enabledTypes.map((t) => ({ value: t.code, label: t.label, testid: 'type-chip-' + t.code }))"
                @update:model-value="dr.activityType = ($event as number); dirty = true"
              />
            </label>
            <label>优先级 (越小越优先)<input v-model="dr.priority" type="number" /></label>
            <label>库存<input v-model="dr.inventory" type="number" /></label>
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
        <Section v-if="dr.activityType === 1" :num="2" title="红包规则">
          <div class="seg">
            <button class="chip" :class="{ 'chip-active': dr.redMode === 'fixed' }" @click="dr.redMode = 'fixed'; dirty = true">固定金额</button>
            <button class="chip" :class="{ 'chip-active': dr.redMode === 'ladder' }" @click="dr.redMode = 'ladder'; dirty = true">阶梯分档</button>
          </div>
          <div v-if="dr.redMode === 'fixed'" class="fg">
            <label>红包金额<input v-model="dr.amount" type="number" placeholder="0 ~ 999999" data-testid="form-amount" /></label>
            <label>发放方式
              <select v-model.number="dr.takeType"><option v-for="m in distModes" :key="m.code" :value="m.code">{{ m.label }}</option></select>
            </label>
          </div>
          <DynRowTable v-else :rows="dr.ladder" :headers="['起(min)', '止(max,空=无上限)', '奖励(reward)']" :make-row="() => ({ min: '', max: '', reward: '' })" label="阶梯档" v-slot="{ row }">
            <input type="number" v-model="(row as LadderRow).min" />
            <input type="number" v-model="(row as LadderRow).max" />
            <input type="number" v-model="(row as LadderRow).reward" />
          </DynRowTable>
        </Section>
        <Section v-else-if="dr.activityType === 5" :num="3" title="买赠赠品明细">
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
        <Section :num="4" title="商品绑定">
          <div class="seg">
            <button class="chip" :class="{ 'chip-active': dr.bindMode === 'manual' }" @click="dr.bindMode = 'manual'; dirty = true">手动 SPU</button>
            <button class="chip" :class="{ 'chip-active': dr.bindMode === 'pool' }" @click="dr.bindMode = 'pool'; dirty = true">商品池(自动圈选)</button>
          </div>
          <DynRowTable v-if="dr.bindMode === 'manual'" :rows="dr.spu" :headers="['店铺ID', 'SPU ID']" :make-row="() => ({ storeId: 1, spuId: '' })" label="SPU 绑定" :min-width="280" v-slot="{ row }">
            <input type="number" v-model="(row as any).storeId" />
            <input type="number" v-model="(row as any).spuId" data-testid="spu-row-input" />
          </DynRowTable>
          <template v-else>
            <div class="hint">填商品池 ID (demo 种子池为 1)，保存时后端按池规则圈选并自动绑定。</div>
            <DynRowTable :rows="dr.pool" :headers="['Pool ID']" :make-row="() => ({ poolId: '' })" label="商品池" :min-width="160" v-slot="{ row }">
              <input type="number" v-model="(row as any).poolId" />
            </DynRowTable>
          </template>
        </Section>

        <!-- ⑤ 条件树 -->
        <Section :num="5" title="资格条件 (白名单条件树)" desc="空条件树 = 所有用户恒通过。字段/运算符只能从后端白名单选，服务端翻译成受控 Drools，不接受裸 DRL。">
          <ConditionGroup v-if="dictData" :node="dr.tree" :fields="dictData.fields" :operators="dictData.operators" :depth="0" :root="true" :errors="treeErrors" />
          <div class="preview-bar">
            <button class="mini" data-testid="preview-btn" @click="doPreview">预览条件 (试编译)</button>
            <span v-if="previewState.kind !== 'idle'" class="pv-status" :class="'pv-' + previewState.kind" data-testid="preview-status">{{ previewState.msg }}</span>
          </div>
          <div v-if="previewState.drl" class="mono-box">{{ previewState.drl }}</div>
        </Section>

        <!-- ⑥ 合并策略 -->
        <Section :num="6" title="多活动合并策略" desc="注意：策略按 bizLine 生效，会影响同业务线其它活动。">
          <div class="seg">
            <button v-for="s in strategies" :key="s" class="chip" :class="{ 'chip-active': dr.strategy === s }" @click="dr.strategy = s; dirty = true">{{ s }}</button>
          </div>
        </Section>
      </div>

      <!-- 右栏：校验 & 提交 -->
      <aside class="rail">
        <div class="col-label">校验 & 提交</div>
        <Banner v-if="validationErrs.length" kind="warn" data-testid="validation-errs">
          <div v-for="(e, i) in validationErrs" :key="i">· {{ e }}</div>
        </Banner>
        <button class="primary" :disabled="!canSubmit" data-testid="submit" @click="submit">
          {{ submitting ? '提交中…' : (editId ? '保存 (新版本)' : '保存活动') }}
        </button>

        <Card v-if="saved" title="活动已保存" data-testid="save-success">
          <Kv k="活动ID" mono>{{ saved.activityId }}</Kv>
          <Kv k="版本">v{{ saved.version }}</Kv>
          <Kv k="自动圈选绑定">{{ saved.autoBoundCount }} 个</Kv>
          <div v-if="saved.idempotentHit" class="tag-gold" data-testid="idempotent-hit">幂等命中：重复提交返回首次结果</div>
          <Button variant="subtle" size="sm" class="back" @click="router.push({ name: 'activities' })">
            <Icon name="arrow-left" :size="15" /><span>返回列表</span>
          </Button>
        </Card>
        <Banner v-if="submitErr" kind="err" data-testid="conflict-hint">{{ submitErr }}</Banner>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.layout { display: grid; grid-template-columns: 1fr 300px; gap: var(--sp-4); }
.fg { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-3); }
.fg label { display: flex; flex-direction: column; gap: var(--sp-1); font-size: var(--fs-xs); color: var(--text-soft); }
.fg .full { grid-column: 1 / -1; }
.fg input, .fg select, .fg textarea { padding: var(--sp-2); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); font-family: inherit; }
.seg { display: flex; gap: var(--sp-1); flex-wrap: wrap; margin: 0 0 var(--sp-2); }
.chip { padding: var(--sp-1) var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-pill); background: var(--bg-elev); color: var(--text); font-size: 12.5px; cursor: pointer; transition: background .12s ease; }
.chip:hover { background: var(--bg-hover); }
.chip-active, .chip-active:hover { background: var(--accent); color: #fff; border-color: var(--accent); }
.hint { font-size: 12px; color: var(--text-faint); margin: var(--sp-1) 0; }
.preview-bar { display: flex; align-items: center; gap: var(--sp-2); margin: var(--sp-2) 0; }
.mini { padding: var(--sp-1) var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); cursor: pointer; font-size: 12px; }
.pv-status { font-size: 12px; }
.pv-ok { color: var(--green); } .pv-err { color: var(--err); } .pv-pending { color: var(--text-soft); }
.mono-box { font-family: var(--mono); font-size: 12px; background: var(--bg-soft); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: var(--sp-2); white-space: pre-wrap; word-break: break-all; margin: var(--sp-2) 0; }
.rail { align-self: start; position: sticky; top: var(--sp-4); }
.col-label { font-weight: 600; font-size: 13px; margin-bottom: var(--sp-2); }
.primary { width: 100%; background: var(--accent); color: #fff; border: none; border-radius: var(--radius-sm); padding: var(--sp-3); cursor: pointer; font-size: 14px; font-weight: var(--fw-medium); transition: background .12s ease; }
.primary:hover:not(:disabled) { background: var(--accent-hover); }
.primary:disabled { opacity: .5; cursor: not-allowed; }
.tag-gold { background: var(--gold-soft); color: var(--gold); font-size: 12px; padding: var(--sp-1) var(--sp-2); border-radius: var(--radius-sm); margin-top: var(--sp-2); }
.back { margin-top: var(--sp-3); width: 100%; }
@media (max-width: 1023px) { .layout { grid-template-columns: 1fr; } .rail { position: static; } .fg { grid-template-columns: 1fr; } }
</style>
