<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listActivities, changeStatus } from '../activityApi'
import { useDictStore } from '@/stores/useDictStore'
import { useToast } from '@/shared/useToast'
import { errText } from '@/shared/apiClient'
import type { ActivityListRow } from '@/shared/types'
import Skeleton from '@/shared/ui/Skeleton.vue'
import Banner from '@/shared/ui/Banner.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import Button from '@/shared/ui/Button.vue'
import Badge from '@/shared/ui/Badge.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'

const router = useRouter()
const dict = useDictStore()
const toast = useToast()

const rows = ref<ActivityListRow[]>([])
const loading = ref(false)
const loadErr = ref('') // 错误单独态，不再伪装成空态
let ctrl: AbortController | null = null

// 筛选 + 搜索 + 分页（新 UX）
const q = ref('')
const statusFilter = ref<number | ''>('')
const page = ref(1)
const pageSize = 20

const filtered = computed(() => {
  const kw = q.value.trim().toLowerCase()
  return rows.value.filter((r) => {
    if (statusFilter.value !== '' && r.activityStatus !== statusFilter.value) return false
    if (kw && !(`${r.activityName} ${r.activityId} ${r.bizLine || ''}`.toLowerCase().includes(kw))) return false
    return true
  })
})
const paged = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))

function typeLabel(code: number): string {
  return dict.cache['__default__']?.activityTypes.find((t) => t.code === code)?.label ?? String(code)
}
function statusLabel(code: number): string {
  return dict.cache['__default__']?.statuses.find((s) => s.code === code)?.label ?? String(code)
}

async function load(): Promise<void> {
  loading.value = true
  loadErr.value = ''
  ctrl?.abort()
  ctrl = new AbortController()
  try {
    await dict.load()
    const r = await listActivities(ctrl.signal)
    if (!r.ok) {
      loadErr.value = errText(r) // 修现状 bug：错误不再伪装成"暂无活动"
      return
    }
    rows.value = r.json || []
  } catch (e) {
    if ((e as Error).name !== 'AbortError') loadErr.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

async function toggleStatus(row: ActivityListRow): Promise<void> {
  const target = row.activityStatus === 1 ? 2 : 1
  const r = await changeStatus(row.activityId, row.version, target)
  if (!r.ok) {
    toast.err(errText(r))
    return
  }
  toast.ok(target === 1 ? '已上线' : '已下线')
  load()
}

onMounted(load)
onUnmounted(() => ctrl?.abort())
</script>

<template>
  <section data-testid="list-view">
    <PageHeader title="活动列表" subtitle="发现、复核、上下线营销活动">
      <template #actions>
        <Button variant="primary" :to="{ name: 'activity-new' }">＋ 新建活动</Button>
      </template>
    </PageHeader>

    <div class="toolbar">
      <input class="search" v-model="q" placeholder="搜索名称/ID/业务线" data-testid="list-search" />
      <select class="fsel" v-model="statusFilter" data-testid="list-status-filter">
        <option value="">全部状态</option>
        <option :value="1">上线</option>
        <option :value="2">下线</option>
      </select>
      <span class="spacer" />
      <button class="ghost" data-testid="list-refresh" @click="load">刷新</button>
    </div>

    <Skeleton v-if="loading" :rows="5" />
    <Banner v-else-if="loadErr" kind="err" data-testid="list-error">
      加载失败：{{ loadErr }} <button class="retry" @click="load">重试</button>
    </Banner>
    <div v-else-if="!filtered.length" data-testid="list-empty">
      <EmptyState
        :title="rows.length ? '无匹配结果' : '暂无活动'"
        :hint="rows.length ? '换个搜索词或状态筛选试试' : '还没有活动，点右上「新建活动」创建第一个'"
      >
        <template v-if="!rows.length" #action>
          <Button variant="primary" :to="{ name: 'activity-new' }">＋ 新建活动</Button>
        </template>
      </EmptyState>
    </div>
    <template v-else>
      <div class="tbl">
        <div class="tr th">
          <span>活动ID</span><span>名称/业务线</span><span>类型</span><span>状态</span><span>版本</span><span>操作</span>
        </div>
        <div v-for="a in paged" :key="a.activityId" class="tr" :data-testid="'activity-row-' + a.activityId">
          <span class="mono id">{{ a.activityId }}</span>
          <span><div>{{ a.activityName }}</div><div class="sub">{{ a.bizLine || '-' }}</div></span>
          <span>{{ typeLabel(a.activityType) }}</span>
          <span><Badge :kind="a.activityStatus === 1 ? 'ok' : 'neutral'">{{ statusLabel(a.activityStatus) }}</Badge></span>
          <span class="mono">v{{ a.version }}</span>
          <span class="acts">
            <button @click="router.push({ name: 'activity-detail', params: { id: a.activityId } })">详情</button>
            <button @click="router.push({ name: 'activity-edit', params: { id: a.activityId } })">编辑</button>
            <button @click="toggleStatus(a)">{{ a.activityStatus === 1 ? '下线' : '上线' }}</button>
          </span>
        </div>
      </div>
      <div v-if="totalPages > 1" class="pager" data-testid="list-pager">
        <button :disabled="page <= 1" @click="page--">上一页</button>
        <span>{{ page }} / {{ totalPages }}</span>
        <button :disabled="page >= totalPages" @click="page++">下一页</button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; gap: var(--sp-2); margin-bottom: var(--sp-3); flex-wrap: wrap; }
.search, .fsel { padding: var(--sp-2); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); }
.search { flex: 1; min-width: 160px; }
.spacer { flex: 1; }
.ghost { border: 1px solid var(--border); background: var(--bg-soft); color: var(--text); border-radius: var(--radius-sm); padding: var(--sp-2) var(--sp-3); cursor: pointer; }
.retry { margin-left: var(--sp-2); cursor: pointer; }
.tbl { display: flex; flex-direction: column; background: var(--bg-elev); border: 1px solid var(--border); border-radius: var(--radius); box-shadow: var(--shadow-sm); overflow: hidden; }
.tr { display: grid; grid-template-columns: 1.6fr 1.4fr .8fr .8fr .6fr 1.4fr; gap: var(--sp-2); padding: var(--sp-3); border-bottom: 1px solid var(--border); align-items: center; }
.tr:last-child { border-bottom: none; }
.tr:not(.th):hover { background: var(--bg-hover); }
.tr > span { min-width: 0; }  /* 窄内容区(侧栏占位)下让 grid 列可收缩，防 mono ID 撑出横向溢出 */
.tr.th { font-size: var(--fs-xs); color: var(--text-soft); font-weight: var(--fw-semibold); background: var(--bg-soft); }
.id { font-size: var(--fs-xs); overflow-wrap: anywhere; }
.sub { font-size: var(--fs-xs); color: var(--text-faint); }
.mono { font-family: var(--mono); }
.acts { display: flex; gap: var(--sp-1); flex-wrap: wrap; }
.acts button { border: 1px solid var(--border); background: var(--bg-soft); color: var(--text); border-radius: var(--radius-sm); padding: var(--sp-1) var(--sp-2); cursor: pointer; font-size: 12px; }
.pager { display: flex; align-items: center; gap: var(--sp-3); justify-content: center; margin-top: var(--sp-4); }
.pager button { border: 1px solid var(--border); background: var(--bg-soft); border-radius: var(--radius-sm); padding: var(--sp-1) var(--sp-3); cursor: pointer; }
@media (max-width: 760px) {
  .tr { grid-template-columns: 1fr 1fr; }
  .tr.th { display: none; }
  .acts button { min-height: 36px; }
}
@media (pointer: coarse) { .acts button { min-height: var(--touch-min); } }
</style>
