<script setup lang="ts">
/**
 * 详情回显·绑定商品（店铺聚合 + 点击下钻）。
 *
 * <p>替换详情页原先「扁平 v-for 全量绑定行」——自动化绑商品后那份可达万级、拖慢首屏。
 * 这里改为：挂载即拉店铺聚合（O 店铺数一次返回），点某店铺行 accordion 就地展开、
 * 由 {@link BindingSpuList} 服务端分页取该店商品明细。version 由父组件下发（= 详情同版）。
 *
 * <p>店铺无名（全项目 store_id 是裸数字），故只展示「店铺 #id」+ 计数；`storeId=null` 归「未指定门店」桶。
 */
import { computed, onUnmounted, ref, watch } from 'vue'
import { getBindingStores } from '../activityApi'
import { errText } from '@/shared/apiClient'
import type { BindingStoreRow } from '@/shared/types'
import Card from '@/shared/ui/Card.vue'
import Icon from '@/shared/ui/Icon.vue'
import Skeleton from '@/shared/ui/Skeleton.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'
import BindingSpuList from './BindingSpuList.vue'

const props = defineProps<{ activityId: string; version?: number }>()

const stores = ref<BindingStoreRow[]>([])
const loading = ref(false)
const err = ref('')
const expandedKey = ref<string | null>(null)
let ctrl: AbortController | null = null

const total = computed(() => stores.value.reduce((s, r) => s + (r.spuCount || 0), 0))

function keyOf(row: BindingStoreRow): string {
  return row.storeId === null ? '__null__' : String(row.storeId)
}
function storeLabel(row: BindingStoreRow): string {
  return row.storeId === null ? '未指定门店' : '店铺 #' + row.storeId
}

async function load(): Promise<void> {
  loading.value = true
  err.value = ''
  ctrl?.abort()
  const c = new AbortController()
  ctrl = c
  try {
    const res = await getBindingStores(props.activityId, props.version, c.signal)
    if (c.signal.aborted) return
    if (!res.ok) {
      err.value = errText(res)
      return
    }
    stores.value = res.json || []
  } catch (e) {
    if ((e as Error).name !== 'AbortError') err.value = (e as Error).message
  } finally {
    if (!c.signal.aborted) loading.value = false
  }
}

function toggle(row: BindingStoreRow): void {
  const k = keyOf(row)
  expandedKey.value = expandedKey.value === k ? null : k
}

watch(() => [props.activityId, props.version], load, { immediate: true })
onUnmounted(() => ctrl?.abort())
</script>

<template>
  <Card :title="`商品绑定 · ${total}`" data-testid="binding-view">
    <Skeleton v-if="loading" :rows="3" />
    <div v-else-if="err" class="bind-err" role="alert">
      <span>{{ err }}</span>
      <button type="button" @click="load">重试</button>
    </div>
    <EmptyState v-else-if="!stores.length" icon="inbox" title="没有绑定商品" hint="该版本还没有绑定任何店铺商品。" />
    <div v-else class="store-list">
      <div v-for="row in stores" :key="keyOf(row)" class="store-block" :data-testid="`binding-store-${keyOf(row)}`">
        <button
          type="button"
          class="store-row"
          :aria-expanded="expandedKey === keyOf(row)"
          :aria-controls="`binding-spus-${keyOf(row)}`"
          @click="toggle(row)"
        >
          <span class="s-icon"><Icon name="workflow" :size="15" /></span>
          <span class="s-main">
            <strong>{{ storeLabel(row) }}</strong>
            <small>{{ row.spuCount }} 件 · {{ row.effectiveCount }} 生效</small>
          </span>
          <span class="s-toggle">
            <i>查看</i>
            <Icon :name="expandedKey === keyOf(row) ? 'chevron-down' : 'chevron-right'" :size="16" />
          </span>
        </button>
        <div v-if="expandedKey === keyOf(row)" :id="`binding-spus-${keyOf(row)}`" class="store-detail">
          <BindingSpuList :activity-id="activityId" :version="version" :store-id="row.storeId" />
        </div>
      </div>
    </div>
  </Card>
</template>

<style scoped>
.store-list { display: flex; flex-direction: column; }
.store-block { border-bottom: 1px solid var(--border); }
.store-block:last-child { border-bottom: 0; }

.store-row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--sp-2); width: 100%; padding: var(--sp-2) 0; border: 0; background: transparent; color: inherit; text-align: left; cursor: pointer; }
.store-row:hover { color: var(--accent); }
.s-icon { display: inline-flex; padding: 6px; border-radius: 7px; background: var(--bg-soft); color: var(--text-faint); }
.store-row:hover .s-icon { color: var(--accent); }
.s-main { min-width: 0; }
.s-main strong, .s-main small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.s-main strong { font-size: var(--fs-xs); color: var(--text); }
.s-main small { color: var(--text-faint); font-size: var(--fs-2xs); font-variant-numeric: tabular-nums; }
.s-toggle { display: inline-flex; align-items: center; gap: 4px; color: var(--text-soft); font-size: var(--fs-2xs); }
.s-toggle i { font-style: normal; }

.store-detail { padding-bottom: var(--sp-2); }

.bind-err { display: flex; align-items: center; gap: var(--sp-2); color: var(--red); font-size: var(--fs-xs); }
.bind-err button { padding: 2px var(--sp-2); border: 1px solid currentColor; border-radius: var(--radius-sm); background: transparent; color: inherit; cursor: pointer; }

/* 触屏：店铺行整行是触控目标，撑到 44px（DetailView 全页无 coarse 规则，组件自带） */
@media (pointer: coarse) {
  .store-row { min-height: var(--touch-min); }
}
/* 极窄屏：店铺名/计数一行，「查看」跨整行（仿 ListView 卡片范式） */
@media (max-width: 560px) {
  .store-row { grid-template-columns: auto minmax(0, 1fr); }
  .s-toggle { grid-column: 1 / -1; justify-content: flex-end; padding-top: 2px; }
}
</style>
