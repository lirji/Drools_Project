<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { listActivities, changeStatus } from '../activityApi'
import { useDictStore } from '@/stores/useDictStore'
import { useToast } from '@/shared/useToast'
import { useConfirm } from '@/shared/useConfirm'
import { errText } from '@/shared/apiClient'
import type { ActivityListRow } from '@/shared/types'
import Skeleton from '@/shared/ui/Skeleton.vue'
import Banner from '@/shared/ui/Banner.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import Button from '@/shared/ui/Button.vue'
import Badge from '@/shared/ui/Badge.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'
import Icon from '@/shared/ui/Icon.vue'

const router = useRouter()
const dict = useDictStore()
const toast = useToast()
const { confirm } = useConfirm()

const rows = ref<ActivityListRow[]>([])
const loading = ref(false)
const loadErr = ref('')
const pendingStatusId = ref('')
let ctrl: AbortController | null = null
let loadSequence = 0

const q = ref('')
const statusFilter = ref<number | ''>('')
const page = ref(1)
const pageSize = 20

const onlineCount = computed(() => rows.value.filter((row) => row.activityStatus === 1).length)
const offlineCount = computed(() => rows.value.filter((row) => row.activityStatus === 2).length)
const bizLineCount = computed(() => new Set(rows.value.map((row) => row.bizLine).filter(Boolean)).size)
const filtered = computed(() => {
  const keyword = q.value.trim().toLocaleLowerCase()
  return rows.value.filter((row) => {
    if (statusFilter.value !== '' && row.activityStatus !== statusFilter.value) return false
    if (keyword && !`${row.activityName} ${row.activityId} ${row.bizLine || ''}`.toLocaleLowerCase().includes(keyword)) return false
    return true
  })
})
const paged = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const hasFilters = computed(() => !!q.value.trim() || statusFilter.value !== '')

watch([q, statusFilter], () => { page.value = 1 })
watch(totalPages, (total) => { if (page.value > total) page.value = total })

function typeLabel(code: number): string {
  return dict.cache['__default__']?.activityTypes.find((item) => item.code === code)?.label ?? String(code)
}

function statusLabel(code: number): string {
  return dict.cache['__default__']?.statuses.find((item) => item.code === code)?.label ?? String(code)
}

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
    rows.value = response.json || []
  } catch (error) {
    if (sequence === loadSequence && (error as Error).name !== 'AbortError') loadErr.value = (error as Error).message
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function toggleStatus(row: ActivityListRow): Promise<void> {
  const target = row.activityStatus === 1 ? 2 : 1
  const goOffline = target === 2
  const accepted = await confirm({
    title: goOffline ? `下线「${row.activityName}」？` : `上线「${row.activityName}」？`,
    body: goOffline
      ? '下线后该活动立即停止参与决策命中，可再次上线恢复。'
      : '上线后该活动立即参与线上优惠决策。',
    confirmText: goOffline ? '下线' : '上线',
    danger: goOffline,
  })
  if (!accepted) return

  pendingStatusId.value = row.activityId
  try {
    const response = await changeStatus(row.activityId, row.version, target)
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

onMounted(load)
onUnmounted(() => {
  ctrl?.abort()
  loadSequence += 1
})
</script>

<template>
  <section data-testid="list-view">
    <PageHeader title="活动管理" subtitle="在一个页面完成活动检索、复核和上下线">
      <template #actions>
        <Button variant="primary" :to="{ name: 'activity-new' }"><Icon name="plus" :size="16" /> 新建活动</Button>
      </template>
    </PageHeader>

    <div class="stats" aria-label="活动统计">
      <article class="stat total">
        <span class="stat-icon"><Icon name="layers" :size="19" /></span>
        <div><small>全部活动</small><strong>{{ rows.length }}</strong></div>
        <span class="stat-note">当前租户</span>
      </article>
      <article class="stat online">
        <span class="stat-icon"><Icon name="radio" :size="19" /></span>
        <div><small>正在生效</small><strong>{{ onlineCount }}</strong></div>
        <span class="stat-note"><i /> ONLINE</span>
      </article>
      <article class="stat offline">
        <span class="stat-icon"><Icon name="clock" :size="19" /></span>
        <div><small>已下线</small><strong>{{ offlineCount }}</strong></div>
        <span class="stat-note">可重新上线</span>
      </article>
      <article class="stat biz">
        <span class="stat-icon"><Icon name="workflow" :size="19" /></span>
        <div><small>业务线</small><strong>{{ bizLineCount }}</strong></div>
        <span class="stat-note">独立合并策略</span>
      </article>
    </div>

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
          </select>
          <Icon name="chevron-down" :size="14" />
        </label>
        <button v-if="hasFilters" class="clear-filter" type="button" @click="clearFilters">清除筛选</button>
        <span class="spacer" />
        <button class="refresh" type="button" :disabled="loading" data-testid="list-refresh" @click="load">
          <Icon name="refresh" :size="15" :class="{ spinning: loading }" /> {{ loading ? '刷新中' : '刷新' }}
        </button>
      </div>

      <Skeleton v-if="loading && !rows.length" :rows="5" />
      <Banner v-else-if="loadErr" kind="err" role="alert" data-testid="list-error">
        <strong>活动列表加载失败</strong><span>{{ loadErr }}</span><button class="retry" type="button" @click="load">重新加载</button>
      </Banner>
      <div v-else-if="!filtered.length" data-testid="list-empty">
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
        <div class="tbl" :aria-busy="loading">
          <div class="tr th">
            <span>活动</span><span>业务线 / 类型</span><span>状态</span><span>版本</span><span>操作</span>
          </div>
          <article v-for="activity in paged" :key="activity.activityId" class="tr" :data-testid="'activity-row-' + activity.activityId">
            <span class="activity-main" data-label="活动">
              <button type="button" class="activity-name" @click="router.push({ name: 'activity-detail', params: { id: activity.activityId } })">
                {{ activity.activityName }} <Icon name="arrow-up-right" :size="14" />
              </button>
              <small class="mono">{{ activity.activityId }}</small>
            </span>
            <span class="classification" data-label="业务线 / 类型">
              <strong>{{ activity.bizLine || '未分类' }}</strong><small>{{ typeLabel(activity.activityType) }}</small>
            </span>
            <span data-label="状态">
              <Badge :kind="activity.activityStatus === 1 ? 'ok' : 'neutral'">
                <i class="status-dot" /> {{ statusLabel(activity.activityStatus) }}
              </Badge>
            </span>
            <span class="version mono" data-label="版本">v{{ activity.version }}</span>
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
  </section>
</template>

<style scoped>
.stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--sp-3); margin-bottom: var(--sp-5); }
.stat { display: grid; grid-template-columns: auto 1fr; gap: 0 var(--sp-3); align-items: center; min-width: 0; padding: var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); box-shadow: var(--shadow-sm); }
.stat-icon { grid-row: span 2; display: inline-flex; align-items: center; justify-content: center; width: 40px; height: 40px; border-radius: 11px; background: var(--accent-soft); color: var(--accent); }
.stat.online .stat-icon { background: var(--green-soft); color: var(--green); }.stat.offline .stat-icon { background: var(--gold-soft); color: var(--gold); }.stat.biz .stat-icon { background: var(--blue-soft); color: var(--blue); }
.stat div { display: flex; align-items: baseline; justify-content: space-between; gap: var(--sp-2); }.stat small { color: var(--text-soft); font-size: 11px; }.stat strong { font-size: 22px; font-variant-numeric: tabular-nums; line-height: 1; }
.stat-note { color: var(--text-faint); font-size: 9px; }.stat-note i { display: inline-block; width: 6px; height: 6px; margin-right: 4px; border-radius: 50%; background: var(--green); box-shadow: 0 0 0 3px var(--green-soft); }
.workspace { overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); box-shadow: var(--shadow-sm); }
.workspace-head { display: flex; align-items: end; justify-content: space-between; gap: var(--sp-3); padding: var(--sp-4) var(--sp-5) var(--sp-3); }
.kicker { color: var(--accent); font-size: 9px; font-weight: var(--fw-bold); letter-spacing: .12em; }.workspace-head h2 { margin: 2px 0 0; font-size: var(--fs-lg); }.result-count { color: var(--text-faint); font-size: var(--fs-xs); }
.toolbar { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-3) var(--sp-5); border-top: 1px solid var(--border); border-bottom: 1px solid var(--border); background: var(--bg-soft); }
.search-box, .status-select { display: flex; align-items: center; gap: var(--sp-2); min-height: 38px; padding: 0 var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text-faint); }
.search-box { flex: 0 1 360px; }.search-box:focus-within, .status-select:focus-within { border-color: var(--accent); box-shadow: var(--focus-ring); }.search-box input { width: 100%; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--text); font: inherit; font-size: var(--fs-sm); }.search-box button { display: inline-flex; padding: 2px; border: 0; background: transparent; color: var(--text-faint); cursor: pointer; }
.status-select select { appearance: none; min-width: 105px; border: 0; outline: 0; background: transparent; color: var(--text); font: inherit; font-size: var(--fs-xs); cursor: pointer; }.clear-filter { border: 0; background: transparent; color: var(--accent); cursor: pointer; font: inherit; font-size: var(--fs-xs); }.spacer { flex: 1; }.refresh { display: inline-flex; align-items: center; gap: var(--sp-1); padding: var(--sp-2) var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text-soft); cursor: pointer; font: inherit; font-size: var(--fs-xs); }.refresh:disabled { cursor: wait; opacity: .7; }.spinning { animation: spin .9s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
.retry { margin-left: var(--sp-2); border: 0; background: transparent; color: inherit; cursor: pointer; font-weight: var(--fw-semibold); text-decoration: underline; }.empty-action { padding: var(--sp-2) var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); cursor: pointer; }
.tbl { display: flex; flex-direction: column; }.tr { display: grid; grid-template-columns: minmax(220px, 1.7fr) minmax(140px, 1fr) .7fr .45fr minmax(210px, 1.25fr); gap: var(--sp-3); align-items: center; min-width: 0; padding: var(--sp-3) var(--sp-5); border-bottom: 1px solid var(--border); }.tr.th { min-height: 38px; padding-top: var(--sp-2); padding-bottom: var(--sp-2); background: var(--bg-soft); color: var(--text-faint); font-size: 10px; font-weight: var(--fw-bold); letter-spacing: .04em; text-transform: uppercase; }.tr:not(.th):hover { background: color-mix(in srgb, var(--bg-hover) 62%, transparent); }.tr > span { min-width: 0; }
.activity-main, .classification { display: flex; min-width: 0; flex-direction: column; align-items: flex-start; }.activity-name { display: inline-flex; align-items: center; gap: var(--sp-1); max-width: 100%; overflow: hidden; padding: 0; border: 0; background: transparent; color: var(--text); cursor: pointer; font: inherit; font-weight: var(--fw-semibold); text-align: left; text-overflow: ellipsis; white-space: nowrap; }.activity-name:hover { color: var(--accent); }.activity-main small, .classification small { max-width: 100%; overflow: hidden; margin-top: 2px; color: var(--text-faint); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }.classification strong { font-size: var(--fs-xs); }.version { color: var(--text-soft); font-size: var(--fs-xs); }.mono { font-family: var(--mono); }.status-dot { display: inline-block; width: 6px; height: 6px; margin-right: 3px; border-radius: 50%; background: currentColor; }
.acts { display: flex; gap: var(--sp-1); flex-wrap: wrap; }.acts button { min-height: 30px; padding: var(--sp-1) var(--sp-2); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text-soft); cursor: pointer; font: inherit; font-size: 11px; }.acts button:hover { background: var(--bg-hover); color: var(--text); }.acts .detail { border-color: var(--accent-line); color: var(--accent); }.acts .online { border-color: var(--green-soft); color: var(--green); }.acts .offline { color: var(--err); }.acts button:disabled { cursor: wait; opacity: .6; }
.table-footer { display: flex; align-items: center; justify-content: space-between; min-height: 48px; padding: var(--sp-2) var(--sp-5); color: var(--text-faint); font-size: 10px; }.pager { display: flex; align-items: center; gap: var(--sp-2); }.pager button { display: inline-flex; padding: 6px; border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text-soft); cursor: pointer; }.pager button:disabled { opacity: .4; cursor: not-allowed; }.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; }
@media (max-width: 1180px) { .stats { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 1023px) {
  .workspace { overflow: visible; border: 0; background: transparent; box-shadow: none; }.workspace-head { padding-right: 0; padding-left: 0; }.toolbar { flex-wrap: wrap; padding: var(--sp-3); border: 1px solid var(--border); border-radius: var(--radius-lg); }.search-box { flex: 1 1 240px; }.tbl { gap: var(--sp-3); margin-top: var(--sp-3); }.tr.th { display: none; }.tr { display: grid; grid-template-columns: 1fr 1fr; gap: 0 var(--sp-4); padding: var(--sp-3) var(--sp-4); border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--bg-elev); box-shadow: var(--shadow-sm); }.tr:not(.th):hover { background: var(--bg-elev); border-color: var(--border-strong); }.tr > span { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); min-height: 38px; border-bottom: 1px solid var(--border); }.tr > span::before { content: attr(data-label); flex: 0 0 auto; color: var(--text-faint); font-size: 10px; }.tr .activity-main { grid-column: 1 / -1; align-items: flex-start; justify-content: center; padding-bottom: var(--sp-2); }.tr .activity-main::before { display: none; }.tr .classification { align-items: flex-end; }.tr .acts { grid-column: 1 / -1; justify-content: flex-start; padding-top: var(--sp-2); border-bottom: 0; }.tr .acts::before { display: none; }.tr .acts button { flex: 1; min-width: 80px; min-height: 38px; }.table-footer { padding-right: 0; padding-left: 0; }
}
@media (max-width: 560px) { .stats { grid-template-columns: 1fr 1fr; }.stat { display: flex; align-items: center; padding: var(--sp-3); }.stat-icon { width: 34px; height: 34px; }.stat div { flex: 1; flex-direction: column; align-items: flex-start; }.stat strong { font-size: 18px; }.stat-note { display: none; }.toolbar { align-items: stretch; flex-direction: column; }.search-box, .status-select { width: 100%; }.spacer { display: none; }.refresh { align-self: flex-end; }.tr { grid-template-columns: 1fr; }.tr > span { grid-column: 1; }.tr .classification { align-items: flex-end; }.table-footer { align-items: flex-start; flex-direction: column; gap: var(--sp-2); } }
@media (pointer: coarse) { .acts button { min-height: var(--touch-min); } }
</style>
