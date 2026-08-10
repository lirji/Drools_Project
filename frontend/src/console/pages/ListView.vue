<script setup lang="ts">
/**
 * 活动工作台（PR-5）。相对上一版的三件实质变化：
 *
 * 1. **一行一活动**。`GET /list` 返回的是「行」不是「活动」——P0-4 之后编辑已上线活动会保留线上 v1
 *    另建草稿 v2，两行都未删除。旧实现直接 `v-for` 这批行，于是同一活动出现两次、
 *    Vue `:key` 与 `activity-row-{id}` 同时重复。现在按 activityId 归并（见 benchModel）。
 * 2. **批量操作带显式版本**。批量下线要停的是**正在服务的那一版**；打到草稿等于线上继续发钱。
 * 3. **只画有数据源的东西**。额度量筒 / 今日命中 / 回退率在后端都没有数据源
 *    （inventory 是声明式、决策不扣减；决策指标没有 activityId 标签），按 D6 一律不画假图，
 *    改成说明卡记账「待建接口」。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { listActivities, changeStatus, bulkChangeStatus, getDetail } from '../activityApi'
import {
  mergeRows, filterRows, sortRows, nextSortDir, pruneSelection, summarize, versionForTarget,
  type BenchRow, type BenchState, type SortKey, type SortDir,
} from '../benchModel'
import { useDictStore } from '@/stores/useDictStore'
import { useToast } from '@/shared/useToast'
import { useConfirm } from '@/shared/useConfirm'
import { useDensity, type Density } from '@/shared/useDensity'
import { errText } from '@/shared/apiClient'
import { benefitFormOf, type BenefitForm } from '../logic'
import type { ActivityListRow, BulkStatusResult } from '@/shared/types'
import Skeleton from '@/shared/ui/Skeleton.vue'
import Banner from '@/shared/ui/Banner.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import Button from '@/shared/ui/Button.vue'
import Badge from '@/shared/ui/Badge.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'
import Icon from '@/shared/ui/Icon.vue'
import Segmented from '@/shared/ui/Segmented.vue'
import SidePanel from '@/shared/ui/SidePanel.vue'
import WindowBar from '@/shared/viz/WindowBar.vue'
import BulkBar from '../BulkBar.vue'
import BulkConfirm from '../BulkConfirm.vue'

const router = useRouter()
const dict = useDictStore()
const toast = useToast()
const { confirm } = useConfirm()
const { density, setDensity } = useDensity()

const raw = ref<ActivityListRow[]>([])
const loading = ref(false)
const loadErr = ref('')
const pendingStatusId = ref('')
/** 取数时刻。甘特轴与派生态都读它——每行各调一次 Date.now() 会让同一屏的游标不在一条线上 */
const now = ref(Date.now())
let ctrl: AbortController | null = null
let loadSequence = 0

const q = ref('')
const statusFilter = ref<number | ''>('')
const page = ref(1)
const pageSize = 20
const sortKey = ref<SortKey>('window')
const sortDir = ref<SortDir>(null)

const selected = ref<Set<string>>(new Set())
const bulkBusy = ref(false)
const confirmTarget = ref<1 | 2 | null>(null)

type PanelMode = { kind: 'detail'; id: string } | { kind: 'receipt' }
const panel = ref<PanelMode | null>(null)
const detail = ref<Record<string, unknown> | null>(null)
const detailErr = ref('')
const detailLoading = ref(false)
const receipt = ref<BulkStatusResult | null>(null)

// ---------------------------------------------------------------- 派生数据

const rows = computed(() => mergeRows(raw.value, now.value))
const stats = computed(() => summarize(rows.value, now.value))
const filtered = computed(() =>
  sortRows(filterRows(rows.value, q.value, statusFilter.value), sortKey.value, sortDir.value))
const paged = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const hasFilters = computed(() => !!q.value.trim() || statusFilter.value !== '')

const selectedRows = computed(() => filtered.value.filter((r) => selected.value.has(r.activityId)))
const pageAllSelected = computed(() =>
  paged.value.length > 0 && paged.value.every((r) => selected.value.has(r.activityId)))
const pageSomeSelected = computed(() =>
  paged.value.some((r) => selected.value.has(r.activityId)) && !pageAllSelected.value)
const allMatchedSelected = computed(() =>
  filtered.value.length > 0 && filtered.value.every((r) => selected.value.has(r.activityId)))

watch([q, statusFilter], () => {
  page.value = 1
  // 看不见的行不能被批量操作带走
  selected.value = pruneSelection(selected.value, filtered.value)
})
watch(totalPages, (total) => { if (page.value > total) page.value = total })

// ---------------------------------------------------------------- 展示映射

const STATE_LABEL: Record<BenchState, string> = {
  live: '生效中', warmup: '预热中', draft: '待上线', expired: '已过期', offline: '已下线',
}
/** 状态的**形状**编码，与颜色正交——色觉障碍、灰度打印、余光扫一屏这三种场景下颜色都不可靠 */
const STATE_SHAPE: Record<BenchState, 'dot' | 'square' | 'triangle' | 'ring' | 'hatch'> = {
  live: 'dot', warmup: 'triangle', draft: 'square', expired: 'ring', offline: 'hatch',
}
const STATE_KIND: Record<BenchState, 'ok' | 'blue' | 'neutral' | 'warn'> = {
  live: 'ok', warmup: 'blue', draft: 'neutral', expired: 'warn', offline: 'neutral',
}
/** 甘特条只有三种画法：实心 = 正在服务，蓝斜纹 = 尚未生效，灰斜纹 = 不再服务 */
const STATE_BAR: Record<BenchState, 'live' | 'warmup' | 'ended'> = {
  live: 'live', warmup: 'warmup', draft: 'warmup', expired: 'ended', offline: 'ended',
}

function typeLabel(code: number): string {
  return dict.cache['__default__']?.activityTypes.find((item) => item.code === code)?.label ?? String(code)
}

function fmtDate(t: number | null): string {
  if (t === null) return '—'
  const d = new Date(t)
  return `${d.getMonth() + 1}-${String(d.getDate()).padStart(2, '0')}`
}

const SORT_LABEL: Record<SortKey, string> = { name: '活动', window: '生效窗', status: '状态', version: '版本' }

function toggleSort(key: SortKey): void {
  if (sortKey.value === key) sortDir.value = nextSortDir(sortDir.value)
  else { sortKey.value = key; sortDir.value = 'asc' }
}
function sortMark(key: SortKey): string {
  if (sortKey.value !== key || sortDir.value === null) return ''
  return sortDir.value === 'asc' ? '▲' : '▼'
}
function ariaSort(key: SortKey): 'ascending' | 'descending' | 'none' {
  if (sortKey.value !== key || sortDir.value === null) return 'none'
  return sortDir.value === 'asc' ? 'ascending' : 'descending'
}

// ---------------------------------------------------------------- 取数

function clearFilters(): void {
  q.value = ''
  statusFilter.value = ''
}

async function load(): Promise<void> {
  const sequence = ++loadSequence
  loading.value = true
  loadErr.value = ''
  ctrl?.abort()
  const controller = new AbortController()
  ctrl = controller
  try {
    await dict.load()
    const response = await listActivities(controller.signal)
    if (sequence !== loadSequence) return
    if (!response.ok) {
      loadErr.value = errText(response)
      return
    }
    now.value = Date.now()
    raw.value = response.json || []
    selected.value = pruneSelection(selected.value, filtered.value)
  } catch (error) {
    if (sequence === loadSequence && (error as Error).name !== 'AbortError') loadErr.value = (error as Error).message
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

// ---------------------------------------------------------------- 选择

function toggleRow(id: string): void {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}

function togglePage(): void {
  const next = new Set(selected.value)
  if (pageAllSelected.value) paged.value.forEach((r) => next.delete(r.activityId))
  else paged.value.forEach((r) => next.add(r.activityId))
  selected.value = next
}

function selectAllMatched(): void {
  selected.value = new Set(filtered.value.map((r) => r.activityId))
}

// ---------------------------------------------------------------- 单条上下线

async function toggleStatus(row: BenchRow): Promise<void> {
  const target: 1 | 2 = row.activityStatus === 1 ? 2 : 1
  const version = versionForTarget(row, target)
  const goOffline = target === 2
  const accepted = await confirm({
    title: goOffline ? `下线「${row.activityName}」v${version}？` : `上线「${row.activityName}」v${version}？`,
    body: goOffline
      ? '下线后该活动立即停止参与决策命中，可再次上线恢复。'
      : '上线是一次真实发布：会推进发布代际，并退役该活动其它仍在线的版本。',
    confirmText: goOffline ? '下线' : '上线',
    danger: goOffline,
  })
  if (!accepted) return

  pendingStatusId.value = row.activityId
  try {
    const response = await changeStatus(row.activityId, version, target)
    if (!response.ok) {
      toast.err(errText(response))
      return
    }
    toast.ok(target === 1 ? '活动已上线' : '活动已下线')
    await load()
  } catch (error) {
    toast.err((error as Error).message)
  } finally {
    pendingStatusId.value = ''
  }
}

// ---------------------------------------------------------------- 批量

function askBulk(target: 1 | 2): void {
  if (!selectedRows.value.length) return
  confirmTarget.value = target
}

async function runBulk(): Promise<void> {
  const target = confirmTarget.value
  if (target === null) return
  const items = selectedRows.value.map((r) => ({ activityId: r.activityId, version: versionForTarget(r, target) }))
  confirmTarget.value = null
  bulkBusy.value = true
  try {
    const response = await bulkChangeStatus(items, target)
    if (!response.ok || !response.json) {
      toast.err(errText(response))
      return
    }
    receipt.value = response.json
    const ok = response.json.succeeded.length
    const bad = response.json.failed.length
    // 回执**不自动消失**：服务端没有撤销窗口，这张回执是运营唯一一次看到「哪几个没成」的机会，
    // 关掉它应该是用户的决定，不是计时器的。
    toast.show(`批量${target === 1 ? '上线' : '下线'}：${ok} 成功 · ${bad} 失败`, {
      kind: bad ? 'warn' : 'ok',
      ttl: 0,
      actions: [{ label: '查看回执', keepOpen: true, testid: 'toast-view-receipt', onClick: openReceipt }],
    })
    if (bad) openReceipt()
    selected.value = new Set()
    await load()
  } catch (error) {
    toast.err((error as Error).message)
  } finally {
    bulkBusy.value = false
  }
}

// ---------------------------------------------------------------- 侧板

function openReceipt(): void {
  panel.value = { kind: 'receipt' }
}

async function openDetail(row: BenchRow): Promise<void> {
  panel.value = { kind: 'detail', id: row.activityId }
  detail.value = null
  detailErr.value = ''
  detailLoading.value = true
  try {
    const response = await getDetail(row.activityId)
    // 期间用户可能已经点了别的行或关掉了侧板——晚到的响应不能覆盖当前内容
    if (panel.value?.kind !== 'detail' || panel.value.id !== row.activityId) return
    if (!response.ok) { detailErr.value = errText(response); return }
    detail.value = response.json
  } catch (error) {
    detailErr.value = (error as Error).message
  } finally {
    detailLoading.value = false
  }
}

function closePanel(): void {
  panel.value = null
}

const panelRow = computed(() => {
  const p = panel.value
  if (p?.kind !== 'detail') return null
  return rows.value.find((r) => r.activityId === p.id) ?? null
})

/** getDetail 取的是**最高版本**，而列表行可能是较低的线上版——不说清楚，用户会以为看的就是那一行 */
const detailVersion = computed(() => {
  const manage = detail.value?.manage as { version?: number } | undefined
  return manage?.version ?? null
})
const versionMismatch = computed(() =>
  detailVersion.value !== null && panelRow.value !== null && detailVersion.value !== panelRow.value.version)

const BENEFIT_LABELS: Record<BenefitForm, string> = {
  fixed: '固定金额', random: '随机金额', ladder: '阶梯分档', ratio: '折扣', price: '一口价', nth: '第 N 件折',
}
const panelBenefitLabel = computed(() => {
  if (panelRow.value?.activityType === 6) return '加价购'
  if (panelRow.value?.activityType === 5) return '买赠'
  const rules = detail.value?.rules
  const firstRule = Array.isArray(rules) ? rules[0] : null
  return BENEFIT_LABELS[benefitFormOf(firstRule as Record<string, unknown>).form]
})

function countOf(key: string): number {
  const v = detail.value?.[key]
  return Array.isArray(v) ? v.length : 0
}

const DENSITY_OPTS = [
  { value: 'comfy', label: '舒适', testid: 'density-comfy' },
  { value: 'compact', label: '紧凑', testid: 'density-compact' },
]

onMounted(load)
onUnmounted(() => {
  ctrl?.abort()
  loadSequence += 1
})
</script>

<template>
  <section data-testid="list-view">
    <PageHeader kicker="ACTIVITY BENCH" title="活动工作台" subtitle="检索、复核、批量上下线——一屏完成">
      <template #actions>
        <Segmented
          class="density"
          aria-label="表格密度"
          :model-value="density"
          :options="DENSITY_OPTS"
          @update:model-value="(v) => setDensity(v as Density)"
        />
        <Button variant="primary" :to="{ name: 'activity-new' }"><Icon name="plus" :size="16" /> 新建活动</Button>
      </template>
    </PageHeader>

    <div class="stats">
      <article class="stat">
        <span class="stat-icon"><Icon name="radio" :size="19" /></span>
        <div>
          <small>正在生效</small>
          <strong>{{ stats.live }}<i>/ {{ stats.total }}</i></strong>
        </div>
        <span class="stat-note">
          {{ stats.endingSoon ? `其中 ${stats.endingSoon} 个 7 日内到期` : '无 7 日内到期' }}
        </span>
      </article>

      <!-- D6 诚实性：决策侧指标没有聚合接口，也没有按 activityId 打标。不画假图，改成记账。 -->
      <aside class="notyet" data-testid="metrics-notice">
        <strong>决策指标尚未接入</strong>
        <p>
          决策量、优惠命中率、规则回退率与 P99 目前只在 Prometheus（<code>:9090</code>）与 Grafana（<code>:3001</code>）可见：
          指标未按 activityId 打标，控制台也没有对应的聚合接口，因此这里不显示按活动的命中量与回退率。
        </p>
        <p class="todo">待建：<code>GET /decision/v1/metrics</code> · <code>GET /decision/v1/by-activity</code></p>
      </aside>
    </div>

    <div class="bench" :class="{ 'panel-open': !!panel }">
      <div class="bench-main">
        <div class="workspace">
          <div class="workspace-head">
            <div><span class="kicker">ACTIVITY INVENTORY</span><h2>活动清单</h2></div>
            <span class="result-count">{{ hasFilters ? `筛选出 ${filtered.length} 项` : `共 ${rows.length} 项` }}</span>
          </div>

          <div class="toolbar">
            <label class="search-box">
              <Icon name="search" :size="17" />
              <span class="sr-only">搜索活动</span>
              <input v-model="q" type="search" placeholder="搜索活动名称、ID 或业务线…" data-testid="list-search" />
              <button v-if="q" type="button" aria-label="清空搜索" @click="q = ''"><Icon name="x" :size="15" /></button>
            </label>
            <label class="status-select">
              <Icon name="gauge" :size="16" />
              <span class="sr-only">状态筛选</span>
              <select v-model="statusFilter" data-testid="list-status-filter">
                <option value="">全部状态</option>
                <option :value="1">仅看上线</option>
                <option :value="2">仅看下线</option>
                <option :value="0">仅看草稿</option>
              </select>
              <Icon name="chevron-down" :size="14" />
            </label>
            <button v-if="hasFilters" class="clear-filter" type="button" @click="clearFilters">清除筛选</button>
            <span class="spacer" />
            <button class="refresh" type="button" :disabled="loading" data-testid="list-refresh" @click="load">
              <Icon name="refresh" :size="15" :class="{ spinning: loading }" /> {{ loading ? '刷新中' : '刷新' }}
            </button>
          </div>

          <BulkBar
            v-if="selected.size"
            :count="selected.size"
            :matched="filtered.length"
            :page-all-selected="pageAllSelected"
            :all-matched-selected="allMatchedSelected"
            :busy="bulkBusy"
            @select-all-matched="selectAllMatched"
            @clear="selected = new Set()"
            @bulk="askBulk"
          />

          <Skeleton v-if="loading && !raw.length" :rows="5" />
          <Banner v-else-if="loadErr" kind="err" role="alert" data-testid="list-error">
            <strong>活动列表加载失败</strong><span>{{ loadErr }}</span>
            <button class="retry" type="button" @click="load">重新加载</button>
          </Banner>
          <div v-else-if="!filtered.length" data-testid="list-empty">
            <!-- 提示语**不得回显搜索关键词**：e2e 的跨租户隔离断言读的是 list-view 的 innerText，
                 回显会让它把自己搜的那个活动名当成「泄漏」，报出最吓人的那条安全失败。 -->
            <EmptyState
              :icon="rows.length ? 'search' : 'inbox'"
              :title="rows.length ? '没有匹配的活动' : '还没有活动'"
              :hint="rows.length ? '换个关键词，或清除状态筛选后再试' : '创建第一个活动，配置资格规则和优惠内容'"
            >
              <template #action>
                <button v-if="rows.length" class="empty-action" type="button" @click="clearFilters">清除筛选</button>
                <Button v-else variant="primary" :to="{ name: 'activity-new' }"><Icon name="plus" :size="16" /> 新建活动</Button>
              </template>
            </EmptyState>
          </div>

          <template v-else>
            <!-- 宽内容在自己的容器里横滚，body 永不横滚 -->
            <div class="tbl-scroll">
              <div class="tbl" :aria-busy="loading">
                <div class="tr th">
                  <span class="cell-check">
                    <input
                      type="checkbox"
                      aria-label="选中本页全部活动"
                      data-testid="select-page"
                      :checked="pageAllSelected"
                      :indeterminate="pageSomeSelected"
                      @change="togglePage"
                    />
                  </span>
                  <span :aria-sort="ariaSort('name')">
                    <button type="button" class="sort" data-testid="sort-name" @click="toggleSort('name')">
                      {{ SORT_LABEL.name }}<i>{{ sortMark('name') }}</i>
                    </button>
                  </span>
                  <span>类型</span>
                  <span :aria-sort="ariaSort('window')">
                    <button type="button" class="sort" data-testid="sort-window" @click="toggleSort('window')">
                      {{ SORT_LABEL.window }}<i>{{ sortMark('window') }}</i>
                    </button>
                  </span>
                  <span title="库存为声明式：决策链路不读取、不扣减，且没有已用量统计">额度<sup>*</sup></span>
                  <span :aria-sort="ariaSort('status')">
                    <button type="button" class="sort" data-testid="sort-status" @click="toggleSort('status')">
                      {{ SORT_LABEL.status }}<i>{{ sortMark('status') }}</i>
                    </button>
                  </span>
                  <span>操作</span>
                </div>

                <article
                  v-for="activity in paged"
                  :key="activity.activityId"
                  class="tr"
                  :class="{ on: selected.has(activity.activityId) }"
                  :data-testid="'activity-row-' + activity.activityId"
                >
                  <span class="cell-check">
                    <input
                      type="checkbox"
                      :aria-label="`选中 ${activity.activityName}`"
                      :data-testid="'row-check-' + activity.activityId"
                      :checked="selected.has(activity.activityId)"
                      @change="toggleRow(activity.activityId)"
                    />
                  </span>

                  <span class="activity-main" data-label="活动">
                    <button type="button" class="activity-name" @click="openDetail(activity)">
                      {{ activity.activityName }} <Icon name="arrow-up-right" :size="14" />
                    </button>
                    <small class="mono">
                      {{ activity.activityId }} · v{{ activity.version }}
                      <em v-if="activity.draftVersion" class="draft">草稿 v{{ activity.draftVersion }}</em>
                    </small>
                  </span>

                  <span class="classification" data-label="类型">
                    <strong>{{ typeLabel(activity.activityType) }}</strong>
                    <small>{{ activity.bizLine || '未分类' }}</small>
                  </span>

                  <span class="cell-window" data-label="生效窗">
                    <!-- 所有行共享同一根轴：不读日期就知道「还剩几天 / 几天后开跑 / 已经跑完」，
                         更重要的是能**跨行**看出「这几个活动窗口叠在一起了」——运营最怕的活动打架 -->
                    <WindowBar
                      v-if="activity.start !== null && activity.end !== null"
                      :start="activity.start" :end="activity.end" :now="now"
                      :state="STATE_BAR[activity.state]"
                    />
                    <span v-else class="na">未设时间窗</span>
                  </span>

                  <span class="cell-quota" data-label="额度">
                    <template v-if="activity.inventory !== null">
                      <b class="mono">{{ activity.inventory }}</b><small>声明式</small>
                    </template>
                    <small v-else class="na">未设置</small>
                  </span>

                  <span class="cell-status" data-label="状态">
                    <Badge :kind="STATE_KIND[activity.state]" :shape="STATE_SHAPE[activity.state]">
                      {{ STATE_LABEL[activity.state] }}
                    </Badge>
                    <small class="win mono">{{ fmtDate(activity.start) }} → {{ fmtDate(activity.end) }}</small>
                  </span>

                  <span class="acts">
                    <button type="button" class="detail" @click="router.push({ name: 'activity-detail', params: { id: activity.activityId } })">详情</button>
                    <button type="button" @click="router.push({ name: 'activity-edit', params: { id: activity.activityId } })">编辑</button>
                    <button
                      type="button"
                      :class="activity.activityStatus === 1 ? 'offline' : 'online'"
                      :disabled="pendingStatusId === activity.activityId"
                      @click="toggleStatus(activity)"
                    >
                      {{ pendingStatusId === activity.activityId ? '处理中…' : (activity.activityStatus === 1 ? '下线' : '上线') }}
                    </button>
                  </span>
                </article>
              </div>
            </div>

            <p class="footnote">
              <sup>*</sup> 额度为运营声明值：决策链路不读取、不扣减，也没有已用量统计，因此这里只显示配置数字、不画消耗量具。
            </p>

            <div class="table-footer">
              <span>显示 {{ (page - 1) * pageSize + 1 }}–{{ Math.min(page * pageSize, filtered.length) }}，共 {{ filtered.length }} 项</span>
              <div v-if="totalPages > 1" class="pager" data-testid="list-pager">
                <button type="button" :disabled="page <= 1" aria-label="上一页" @click="page--"><Icon name="arrow-left" :size="14" /></button>
                <span>第 {{ page }} / {{ totalPages }} 页</span>
                <button type="button" :disabled="page >= totalPages" aria-label="下一页" @click="page++"><Icon name="arrow-right" :size="14" /></button>
              </div>
            </div>
          </template>
        </div>
      </div>

      <SidePanel
        v-if="panel"
        :open="!!panel"
        :title="panel.kind === 'receipt' ? '批量操作回执' : (panelRow?.activityName ?? '活动详情')"
        :kicker="panel.kind === 'receipt' ? 'BULK RECEIPT' : panelRow?.activityId"
        @close="closePanel"
      >
        <div v-if="panel.kind === 'receipt'" data-testid="bench-receipt">
          <p class="rc-sum">
            <b>{{ receipt?.succeeded.length ?? 0 }}</b> 成功 ·
            <b :class="{ bad: (receipt?.failed.length ?? 0) > 0 }">{{ receipt?.failed.length ?? 0 }}</b> 失败
          </p>
          <p class="rc-note">
            服务端没有撤销窗口，这张回执不会自动消失。要恢复某个活动，请在列表里对它单独重新上线——
            那是一次<strong>真实发布</strong>，会推进发布代际并退役该活动其它线上版本，不是撤销。
          </p>
          <template v-if="receipt?.failed.length">
            <h3 class="rc-h">失败明细</h3>
            <ul class="rc-list">
              <li v-for="f in receipt.failed" :key="f.activityId">
                <code>{{ f.activityId }}</code><span>{{ f.reason }}</span>
              </li>
            </ul>
          </template>
          <template v-if="receipt?.succeeded.length">
            <h3 class="rc-h">已成功</h3>
            <ul class="rc-list ok">
              <li v-for="id in receipt.succeeded" :key="id"><code>{{ id }}</code></li>
            </ul>
          </template>
        </div>

        <template v-else>
          <Skeleton v-if="detailLoading" :rows="4" />
          <Banner v-else-if="detailErr" kind="err" role="alert">{{ detailErr }}</Banner>
          <div v-else-if="panelRow" data-testid="panel-detail">
            <Banner v-if="versionMismatch" kind="warn">
              这里加载的是最新版本 v{{ detailVersion }}，而列表那一行是正在服务的 v{{ panelRow.version }}。
            </Banner>
            <dl class="kv">
              <dt>状态</dt>
              <dd>
                <Badge :kind="STATE_KIND[panelRow.state]" :shape="STATE_SHAPE[panelRow.state]">{{ STATE_LABEL[panelRow.state] }}</Badge>
              </dd>
              <dt>业务线</dt><dd>{{ panelRow.bizLine || '未分类' }}</dd>
              <dt>类型</dt><dd>{{ typeLabel(panelRow.activityType) }}</dd>
              <dt>权益形态</dt><dd><Badge kind="neutral" shape="square" data-testid="panel-benefit-form">{{ panelBenefitLabel }}</Badge></dd>
              <dt>生效窗</dt><dd class="mono">{{ fmtDate(panelRow.start) }} → {{ fmtDate(panelRow.end) }}</dd>
            </dl>
            <WindowBar
              v-if="panelRow.start !== null && panelRow.end !== null"
              :start="panelRow.start" :end="panelRow.end" :now="now"
              :state="STATE_BAR[panelRow.state]"
            />
            <dl class="kv panel-counts">
              <dt>资格条件</dt><dd>{{ countOf('conditions') }} 条</dd>
              <dt>优惠规则</dt><dd>{{ countOf('rules') }} 条</dd>
              <dt>绑定 SPU</dt><dd>{{ countOf('bindings') }} 个</dd>
              <dt>赠品</dt><dd>{{ countOf('gifts') }} 项</dd>
              <dt>额度</dt>
              <dd>{{ panelRow.inventory ?? '未设置' }} <small class="na">（声明式，决策不扣减）</small></dd>
            </dl>
          </div>
        </template>

        <template #footer>
          <button
            v-if="panel.kind === 'detail' && panelRow"
            type="button" class="panel-go"
            @click="router.push({ name: 'activity-detail', params: { id: panelRow.activityId } })"
          >打开完整详情页</button>
          <button v-else type="button" class="panel-go" @click="closePanel">关闭回执</button>
        </template>
      </SidePanel>
    </div>

    <BulkConfirm
      :open="confirmTarget !== null"
      :target="confirmTarget ?? 2"
      :rows="selectedRows"
      @cancel="confirmTarget = null"
      @confirm="runBulk"
    />
  </section>
</template>

<style scoped>
.density { margin-right: var(--sp-2); }
.stats { display: grid; grid-template-columns: minmax(220px, 300px) minmax(0, 1fr); gap: var(--sp-3); margin-bottom: var(--gap-block); }
.stat { display: grid; grid-template-columns: auto 1fr; gap: 0 var(--sp-3); align-items: center; min-width: 0; padding: var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); box-shadow: var(--shadow-sm); }
.stat-icon { grid-row: span 2; display: inline-flex; align-items: center; justify-content: center; width: 40px; height: 40px; border-radius: var(--radius); background: var(--ok-soft); color: var(--ok); }
.stat div { display: flex; align-items: baseline; justify-content: space-between; gap: var(--sp-2); }
.stat small { color: var(--text-soft); font-size: 11px; }
.stat strong { font-size: 22px; font-variant-numeric: tabular-nums; line-height: 1; }
.stat strong i { margin-left: 3px; color: var(--text-faint); font-size: 13px; font-style: normal; }
.stat-note { color: var(--text-faint); font-size: var(--fs-2xs); }
.notyet { min-width: 0; padding: var(--sp-3) var(--sp-4); border: 1px dashed var(--border-strong); border-radius: var(--radius-lg); background: var(--bg-soft); }
.notyet strong { display: block; margin-bottom: 3px; font-size: var(--fs-sm); }
.notyet p { margin: 0; color: var(--text-faint); font-size: var(--fs-xs); line-height: var(--lh-normal); }
.notyet .todo { margin-top: 4px; }
.notyet code { font-family: var(--mono); font-size: var(--fs-xs); }

.bench { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--gap-group); align-items: start; }
.bench-main { min-width: 0; }
@media (min-width: 1280px) { .bench.panel-open { grid-template-columns: minmax(0, 1fr) var(--panel-w); } }

.workspace { border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); box-shadow: var(--shadow-sm); }
.workspace-head { display: flex; align-items: end; justify-content: space-between; gap: var(--sp-3); padding: var(--sp-4) var(--sp-5) var(--sp-3); }
.kicker { color: var(--accent); font-size: var(--fs-2xs); font-weight: var(--fw-bold); letter-spacing: .12em; }
.workspace-head h2 { margin: 2px 0 0; font-size: var(--fs-lg); }
.result-count { color: var(--text-faint); font-size: var(--fs-xs); }
.toolbar { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-3) var(--sp-5); border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); background: var(--bg-soft); }
.search-box, .status-select { display: flex; align-items: center; gap: var(--sp-2); min-height: 38px; padding: 0 var(--sp-3); border: 1px solid var(--border-ctl); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text-faint); }
.search-box { flex: 0 1 360px; }
.search-box:focus-within, .status-select:focus-within { border-color: var(--accent); box-shadow: var(--focus-ring); }
.search-box input { width: 100%; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--text); font: inherit; font-size: var(--fs-sm); }
.search-box button { display: inline-flex; padding: 2px; border: 0; background: transparent; color: var(--text-faint); cursor: pointer; }
.status-select select { appearance: none; min-width: 105px; border: 0; outline: 0; background: transparent; color: var(--text); font: inherit; font-size: var(--fs-xs); cursor: pointer; }
.clear-filter { border: 0; background: transparent; color: var(--accent); cursor: pointer; font: inherit; font-size: var(--fs-xs); }
.spacer { flex: 1; }
.refresh { display: inline-flex; align-items: center; gap: var(--sp-1); padding: var(--sp-2) var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text-soft); cursor: pointer; font: inherit; font-size: var(--fs-xs); }
.refresh:disabled { cursor: wait; opacity: .7; }
.spinning { animation: spin .9s linear infinite; }

.retry { margin-left: var(--sp-2); border: 0; background: transparent; color: inherit; cursor: pointer; font-weight: var(--fw-semibold); text-decoration: underline; }
.empty-action { padding: var(--sp-2) var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); cursor: pointer; }

.tbl-scroll { overflow-x: auto; }
/* 量具列必须定宽：甘特条的价值来自「跨行同刻度」，一旦弹性伸缩，
   第 1 行的 50% 与第 5 行的 50% 就不在同一横坐标，图在说谎。只有文字主列吃 fr。 */
.tbl { display: flex; flex-direction: column; min-width: 994px; }
.tr {
  display: grid;
  grid-template-columns: 34px minmax(216px, 2.2fr) 128px 178px 110px 132px 168px;
  gap: var(--sp-3); align-items: center; min-width: 0;
  min-height: var(--row-h); padding: var(--row-pad-y) var(--sp-5);
  border-bottom: 1px solid var(--rule-faint); font-size: var(--tbl-fs);
}
.tr.th { min-height: 34px; background: var(--bg-soft); color: var(--text-faint); font-size: var(--fs-xs); font-weight: var(--fw-bold); letter-spacing: .04em; text-transform: uppercase; border-bottom-color: var(--border); }
/* 账簿五行线：密排表里横向追踪最便宜的解 */
.tbl article.tr:nth-of-type(5n) { border-bottom-color: var(--rule); }
.tr:not(.th):hover { background: color-mix(in srgb, var(--bg-hover) 62%, transparent); }
.tr.on { background: var(--accent-soft); }
.tr > span { min-width: 0; }
.cell-check { display: flex; align-items: center; }
.cell-check input { width: 15px; height: 15px; accent-color: var(--accent); cursor: pointer; }
.sort { display: inline-flex; align-items: center; gap: 3px; padding: 0; border: 0; background: transparent; color: inherit; cursor: pointer; font: inherit; text-transform: inherit; letter-spacing: inherit; }
.sort i { font-size: var(--fs-2xs); font-style: normal; color: var(--accent); }
.activity-main, .classification { display: flex; min-width: 0; flex-direction: column; align-items: flex-start; }
.activity-name { display: inline-flex; align-items: center; gap: var(--sp-1); max-width: 100%; overflow: hidden; padding: 0; border: 0; background: transparent; color: var(--text); cursor: pointer; font: inherit; font-weight: var(--fw-semibold); text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.activity-name:hover { color: var(--accent); }
.activity-main small, .classification small { max-width: 100%; overflow: hidden; margin-top: 2px; color: var(--text-faint); font-size: var(--fs-xs); text-overflow: ellipsis; white-space: nowrap; }
.draft { margin-left: 4px; padding: 0 4px; border: 1px solid var(--border); border-radius: var(--radius-sm); font-style: normal; }
.classification strong { font-size: var(--fs-xs); }
.cell-quota { display: flex; align-items: baseline; gap: 4px; }
.cell-quota b { font-size: var(--fs-xs); font-weight: var(--fw-semibold); }
.cell-quota small, .na { color: var(--text-faint); font-size: var(--fs-xs); }
.win { display: block; margin-top: 2px; color: var(--text-faint); font-size: var(--fs-2xs); }
.mono { font-family: var(--mono); }
.acts { display: flex; gap: var(--sp-1); flex-wrap: wrap; }
.acts button { min-height: 28px; padding: var(--sp-1) var(--sp-2); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text-soft); cursor: pointer; font: inherit; font-size: 11px; }
.acts button:hover { background: var(--bg-hover); color: var(--text); }
.acts .detail { border-color: var(--accent-line); color: var(--accent); }
.acts .online { border-color: var(--ok); color: var(--ok); }
.acts .offline { color: var(--err); }
.acts button:disabled { cursor: wait; opacity: .6; }
.footnote { margin: 0; padding: var(--sp-2) var(--sp-5) 0; color: var(--text-faint); font-size: var(--fs-xs); }
.table-footer { display: flex; align-items: center; justify-content: space-between; min-height: 44px; padding: var(--sp-2) var(--sp-5); color: var(--text-faint); font-size: var(--fs-xs); }
.pager { display: flex; align-items: center; gap: var(--sp-2); }
.pager button { display: inline-flex; padding: 6px; border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text-soft); cursor: pointer; }
.pager button:disabled { opacity: .4; cursor: not-allowed; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; }

/* ── 侧板内容 ── */
.kv { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: var(--sp-2) var(--sp-3); margin: 0 0 var(--sp-4); font-size: var(--fs-sm); }
.panel-counts { margin-top: var(--sp-4); }
.kv dt { color: var(--text-faint); font-size: var(--fs-xs); }
.kv dd { margin: 0; min-width: 0; }
.rc-sum { margin: 0 0 var(--sp-2); font-size: var(--fs-lg); }
.rc-sum b { font-family: var(--mono); font-variant-numeric: tabular-nums; }
.rc-sum b.bad { color: var(--err); }
.rc-note { margin: 0 0 var(--sp-4); color: var(--text-faint); font-size: var(--fs-xs); line-height: var(--lh-normal); }
.rc-h { margin: 0 0 var(--sp-2); font-size: var(--fs-xs); color: var(--text-faint); text-transform: uppercase; letter-spacing: .08em; }
.rc-list { margin: 0 0 var(--sp-4); padding: 0; list-style: none; }
.rc-list li { display: flex; flex-direction: column; gap: 2px; padding: var(--sp-2) 0; border-bottom: 1px solid var(--rule-faint); font-size: var(--fs-xs); }
.rc-list code { font-family: var(--mono); font-size: 11px; }
.rc-list li span { color: var(--err); }
.rc-list.ok li span { color: var(--text-faint); }
.panel-go { width: 100%; min-height: 36px; border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); cursor: pointer; font: inherit; font-size: var(--fs-sm); }
.panel-go:hover { background: var(--bg-hover); }

@media (max-width: 1180px) { .stats { grid-template-columns: minmax(0, 1fr); } }
/* ≤1023 平板/手机：表格塌成券卡。**必须解除 min-width**，否则 body 会被撑出横滚。 */
@media (max-width: 1023px) {
  .workspace { border: 0; background: transparent; box-shadow: none; }
  .workspace-head { padding-right: 0; padding-left: 0; }
  .toolbar { flex-wrap: wrap; padding: var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-lg); }
  .search-box { flex: 1 1 240px; }
  .tbl-scroll { overflow-x: visible; }
  .tbl { min-width: 0; gap: var(--sp-3); margin-top: var(--sp-3); }
  .tr.th { display: none; }
  .tr {
    display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 0 var(--sp-4);
    min-height: 0; padding: var(--sp-3) var(--sp-4);
    border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); box-shadow: var(--shadow-sm);
  }
  .tbl article.tr:nth-of-type(5n) { border-bottom-color: var(--border); }
  .tr:not(.th):hover { background: var(--bg-elev); border-color: var(--border-strong); }
  .tr.on { border-color: var(--accent); background: var(--accent-soft); }
  .tr > span { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); min-height: 36px; border-bottom: 1px solid var(--rule-faint); }
  .tr > span::before { content: attr(data-label); flex: 0 0 auto; color: var(--text-faint); font-size: var(--fs-xs); }
  .tr .cell-check { grid-row: 1; grid-column: 1; border-bottom: 0; }
  .tr .cell-check::before { display: none; }
  .tr .activity-main { grid-row: 1; grid-column: 2; align-items: flex-start; justify-content: center; border-bottom: 0; padding-bottom: var(--sp-2); }
  .tr .activity-main::before { display: none; }
  /* 身份行（复选框 + 活动名）之外的每一格都要跨满整行；漏掉任何一个都会让它只占半宽 */
  .tr .classification, .tr .cell-window, .tr .cell-quota, .tr .cell-status { grid-column: 1 / -1; }
  .tr .cell-window { display: block; }
  .tr .cell-window::before { display: block; margin-bottom: 2px; }
  /* 卡片模式下这条虚线就是券的撕口：身份与量具在上，操作在下 */
  .tr .acts { grid-column: 1 / -1; justify-content: flex-start; padding-top: var(--sp-3); margin-top: var(--sp-1); border-top: 1px dashed var(--seam); border-bottom: 0; }
  .tr .acts::before { display: none; }
  .tr .acts button { flex: 1; min-width: 80px; min-height: 36px; }
  .footnote { padding-right: 0; padding-left: 0; }
  .table-footer { padding-right: 0; padding-left: 0; }
}
@media (max-width: 560px) {
  .stat { display: flex; align-items: center; padding: var(--sp-3); }
  .stat-icon { width: 34px; height: 34px; }
  .stat div { flex: 1; flex-direction: column; align-items: flex-start; }
  .stat strong { font-size: 18px; }
  .stat-note { display: none; }
  .toolbar { align-items: stretch; flex-direction: column; }
  /* 缺陷 F3：≤1023 的 `.search-box { flex: 1 1 240px }` 是给 row 方向写的；这里容器转成 column 后，
     240px 从「宽度基准」变成「高度基准」（column 下 flex-basis 即主轴 = 高度，容器高度 auto 时
     flex-grow 无自由空间可分），实测搜索框被撑成 316×240px 的空盒。
     修法只落在这个 ≤560 块内：桌面 `:660` 的 `flex: 0 1 360px` 与 ≤1023 的换行行为都不受影响。 */
  .search-box { flex: 0 0 auto; }
  .search-box, .status-select { width: 100%; }
  .spacer { display: none; }
  .refresh { align-self: flex-end; }
  .tr { grid-template-columns: minmax(0, 1fr); }
  .tr .cell-check { grid-row: auto; grid-column: 1; }
  .tr .activity-main { grid-row: auto; grid-column: 1; }
  .table-footer { align-items: flex-start; flex-direction: column; gap: var(--sp-2); }
  /* 卡片模式下密度切换没有意义（行高由内容决定），隐掉，避免给出一个无效开关 */
  .density { display: none; }
}
/* 选择器必须能压过 ≤1023 卡片模式里的 `.tr .acts button { min-height: 36px }`（0-2-1）——
   只写 `.acts button`（0-1-1）会被它盖掉，触控命中区静默退回 36px。
   e2e:visual 的 A-8 断言就是这条的回归护栏。 */
@media (pointer: coarse) { .tr .acts button, .acts button, .sort { min-height: var(--touch-min); } }
</style>
