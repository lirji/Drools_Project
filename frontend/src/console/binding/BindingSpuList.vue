<script setup lang="ts">
/**
 * 店铺下钻·商品明细（服务端分页）。
 *
 * <p>由 {@link BindingStores} 在某店铺展开时按 `v-if` 挂载，props.storeId 恒定（`null` = 未指定门店桶）。
 * 页缓存（storeId#page）让来回翻页免重复请求；AbortController 丢弃晚到响应防竞态。
 * 分页控件放在可滚动内容内（不放固定 footer），规避移动端 safe-area 遮挡。
 */
import { computed, onUnmounted, ref, watch } from 'vue'
import { getBindingSpus } from '../activityApi'
import { errText } from '@/shared/apiClient'
import type { BindingSpuRow } from '@/shared/types'
import Icon from '@/shared/ui/Icon.vue'
import Skeleton from '@/shared/ui/Skeleton.vue'

const props = defineProps<{ activityId: string; version?: number; storeId: number | null }>()

/** 窄 aside 里每页少放几条（列表页是 20，这里 10）。 */
const SIZE = 10

const items = ref<BindingSpuRow[]>([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const err = ref('')
const cache = new Map<string, { items: BindingSpuRow[]; total: number }>()
let ctrl: AbortController | null = null

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / SIZE)))
const rangeStart = computed(() => (total.value === 0 ? 0 : page.value * SIZE + 1))
const rangeEnd = computed(() => Math.min((page.value + 1) * SIZE, total.value))

function cacheKey(p: number): string {
  return `${props.storeId ?? '__null__'}#${p}`
}

async function loadPage(p: number): Promise<void> {
  const cached = cache.get(cacheKey(p))
  if (cached) {
    items.value = cached.items
    total.value = cached.total
    page.value = p
    return
  }
  loading.value = true
  err.value = ''
  ctrl?.abort()
  const c = new AbortController()
  ctrl = c
  try {
    const res = await getBindingSpus(
      props.activityId,
      { version: props.version, storeId: props.storeId, page: p, size: SIZE },
      c.signal,
    )
    if (c.signal.aborted) return
    if (!res.ok) {
      err.value = errText(res)
      return
    }
    const body = res.json ?? { total: 0, page: p, size: SIZE, items: [] }
    items.value = body.items || []
    total.value = body.total || 0
    page.value = p
    cache.set(cacheKey(p), { items: items.value, total: total.value })
  } catch (e) {
    if ((e as Error).name !== 'AbortError') err.value = (e as Error).message
  } finally {
    if (!c.signal.aborted) loading.value = false
  }
}

function money(v: number | null): string {
  return v == null ? '' : Number(v).toFixed(2)
}
function prev(): void {
  if (page.value > 0) void loadPage(page.value - 1)
}
function next(): void {
  if (page.value < totalPages.value - 1) void loadPage(page.value + 1)
}

watch(
  () => [props.activityId, props.version, props.storeId],
  () => {
    cache.clear()
    void loadPage(0)
  },
  { immediate: true },
)
onUnmounted(() => ctrl?.abort())
</script>

<template>
  <div class="spu-panel">
    <Skeleton v-if="loading && !items.length" :rows="3" />
    <div v-else-if="err" class="bind-err" role="alert">
      <span>{{ err }}</span>
      <button type="button" @click="loadPage(page)">重试</button>
    </div>
    <div v-else-if="!total" class="muted">该店铺暂无商品</div>
    <template v-else>
      <div class="spu-list">
        <div v-for="row in items" :key="row.spuId" class="spu-row" :data-testid="`binding-spu-${row.spuId}`">
          <span class="p-icon"><Icon name="inbox" :size="14" /></span>
          <div class="p-main">
            <strong>{{ row.spuName || ('SPU ' + row.spuId) }}</strong>
            <small>{{ row.bindSource === 1 ? '商品池自动圈选' : '手动绑定'
              }}<template v-if="row.price != null"> · ¥{{ money(row.price) }}</template></small>
          </div>
          <i :class="row.effective === 1 ? 'effective' : 'inactive'">{{ row.effective === 1 ? '生效' : '失效' }}</i>
        </div>
      </div>
      <div class="pager">
        <span class="pg-info">显示 {{ rangeStart }}–{{ rangeEnd }}，共 {{ total }} 项</span>
        <span class="pg-btns">
          <button type="button" data-testid="binding-spu-prev" aria-label="上一页" :disabled="page <= 0" @click="prev">
            <Icon name="arrow-left" :size="14" />
          </button>
          <span class="pg-num">{{ page + 1 }} / {{ totalPages }}</span>
          <button type="button" data-testid="binding-spu-next" aria-label="下一页" :disabled="page >= totalPages - 1" @click="next">
            <Icon name="arrow-right" :size="14" />
          </button>
        </span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.spu-panel { padding: var(--sp-2) 0 var(--sp-1) var(--sp-4); border-left: 2px solid var(--accent-line); }
.spu-list { display: flex; flex-direction: column; }
.spu-row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--sp-2); padding: var(--sp-2) 0; border-bottom: 1px solid var(--border); }
.spu-row:last-child { border-bottom: 0; }
.spu-row > .p-icon { display: inline-flex; padding: 6px; border-radius: 7px; background: var(--bg-soft); color: var(--text-faint); }
.p-main { min-width: 0; }
.p-main strong, .p-main small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.p-main strong { font-size: var(--fs-xs); }
.p-main small { color: var(--text-faint); font-size: var(--fs-2xs); font-variant-numeric: tabular-nums; }
.spu-row > i { padding: 3px 6px; border-radius: var(--radius-pill); font-size: var(--fs-2xs); font-style: normal; }
.spu-row > i.effective { background: var(--green-soft); color: var(--green); }
.spu-row > i.inactive { background: var(--red-soft); color: var(--red); }

.pager { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-2); min-height: 44px; margin-top: var(--sp-1); }
.pg-info { color: var(--text-faint); font-size: var(--fs-2xs); font-variant-numeric: tabular-nums; }
.pg-btns { display: inline-flex; align-items: center; gap: var(--sp-1); }
.pg-num { color: var(--text-soft); font-size: var(--fs-2xs); font-variant-numeric: tabular-nums; }
.pg-btns button { display: inline-flex; align-items: center; justify-content: center; min-width: 30px; padding: 6px; border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text-soft); cursor: pointer; }
.pg-btns button:disabled { opacity: .45; cursor: default; }
.pg-btns button:not(:disabled):hover { background: var(--bg-hover); color: var(--text); }

.bind-err { display: flex; align-items: center; gap: var(--sp-2); color: var(--red); font-size: var(--fs-xs); }
.bind-err button { padding: 2px var(--sp-2); border: 1px solid currentColor; border-radius: var(--radius-sm); background: transparent; color: inherit; cursor: pointer; }
.muted { color: var(--text-faint); font-size: var(--fs-xs); padding: var(--sp-2) 0; }

/* 触屏：分页按钮撑到触控目标（DetailView 全页无 coarse 规则，组件自带） */
@media (pointer: coarse) {
  .pg-btns button { min-width: var(--touch-min); min-height: var(--touch-min); }
}
</style>
