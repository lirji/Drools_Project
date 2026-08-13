<script setup lang="ts">
/**
 * 「选店铺→勾商品」picker（内联展开面板，非模态/非抽屉——与地域 DistrictPicker 的内嵌范式一致）。
 *
 * <p>只负责「录入」：点开 → 选店铺 → 勾该店在架商品（服务端 keyword+分页）→ 「加入绑定」
 * `emit('append', {storeId,spuId}[])`。**不持有 dr.spu、不重写数组**——由 EditorView 的 onPickerAppend
 * 去重 push + markDirty（见计划「实现纪律」）。手填兜底行与本 picker 并存、共写同一 dr.spu。
 *
 * <p>目录**惰性拉**：面板收起时不发请求（避免污染 EditorView 存量测试的 fetch stub）。
 */
import { computed, onUnmounted, ref, watch } from 'vue'
import { listPickerStores, listPickerProducts } from '../activityApi'
import { errText } from '@/shared/apiClient'
import type { PickerProduct, PickerStore } from '@/shared/types'
import { pairKey } from './storeProductPickerLogic'
import Button from '@/shared/ui/Button.vue'
import Icon from '@/shared/ui/Icon.vue'
import Skeleton from '@/shared/ui/Skeleton.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'

const emit = defineEmits<{ (e: 'append', pairs: Array<{ storeId: number; spuId: number }>): void }>()

/** 窄面板每页少放几条。 */
const SIZE = 10

const open = ref(false)

const stores = ref<PickerStore[]>([])
const storesLoading = ref(false)
const storesErr = ref('')
let storesLoaded = false

const selectedStoreId = ref<number | null>(null)
const keyword = ref('')
const products = ref<PickerProduct[]>([])
const total = ref(0)
const page = ref(0)
const productsLoading = ref(false)
const productsErr = ref('')

/** 勾选态：key=`storeId#spuId` → 展示所需的名字。加入绑定时取 storeId/spuId 发出。 */
const selected = ref(new Map<string, { storeId: number; spuId: number; spuName: string | null; storeName: string | null }>())

let storesCtrl: AbortController | null = null
let productsCtrl: AbortController | null = null
let searchTimer: ReturnType<typeof setTimeout> | null = null

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / SIZE)))
const selectedCount = computed(() => selected.value.size)
const currentStoreName = computed(() =>
  stores.value.find((s) => s.storeId === selectedStoreId.value)?.storeName ?? null)

function money(v: number | null): string {
  return v == null ? '' : Number(v).toFixed(2)
}
function storeLabel(s: { storeId: number; storeName: string | null }): string {
  return s.storeName || ('店铺 #' + s.storeId)
}

async function loadStores(): Promise<void> {
  storesLoading.value = true
  storesErr.value = ''
  storesCtrl?.abort()
  const c = new AbortController()
  storesCtrl = c
  try {
    const res = await listPickerStores(c.signal)
    if (c.signal.aborted) return
    if (!res.ok) { storesErr.value = errText(res); return }
    stores.value = res.json || []
    storesLoaded = true
  } catch (e) {
    if ((e as Error).name !== 'AbortError') storesErr.value = (e as Error).message
  } finally {
    if (!c.signal.aborted) storesLoading.value = false
  }
}

async function loadProducts(p: number): Promise<void> {
  if (selectedStoreId.value == null) return
  productsLoading.value = true
  productsErr.value = ''
  productsCtrl?.abort()
  const c = new AbortController()
  productsCtrl = c
  try {
    const res = await listPickerProducts(selectedStoreId.value, { keyword: keyword.value || undefined, page: p, size: SIZE }, c.signal)
    if (c.signal.aborted) return
    if (!res.ok) { productsErr.value = errText(res); return }
    const body = res.json ?? { total: 0, page: p, size: SIZE, items: [] }
    products.value = body.items || []
    total.value = body.total || 0
    page.value = p
  } catch (e) {
    if ((e as Error).name !== 'AbortError') productsErr.value = (e as Error).message
  } finally {
    if (!c.signal.aborted) productsLoading.value = false
  }
}

function toggle(): void {
  open.value = !open.value
  if (open.value && !storesLoaded) void loadStores()
}

function selectStore(id: number): void {
  selectedStoreId.value = id
  keyword.value = ''
  void loadProducts(0)
}

function isChecked(p: PickerProduct): boolean {
  return selectedStoreId.value != null && selected.value.has(pairKey(selectedStoreId.value, p.spuId))
}
function toggleProduct(p: PickerProduct): void {
  if (selectedStoreId.value == null) return
  const k = pairKey(selectedStoreId.value, p.spuId)
  if (selected.value.has(k)) selected.value.delete(k)
  else selected.value.set(k, { storeId: selectedStoreId.value, spuId: p.spuId, spuName: p.spuName, storeName: currentStoreName.value })
}
function removeSelected(k: string): void {
  selected.value.delete(k)
}

function prev(): void { if (page.value > 0) void loadProducts(page.value - 1) }
function next(): void { if (page.value < totalPages.value - 1) void loadProducts(page.value + 1) }

function confirm(): void {
  const pairs = [...selected.value.values()].map((s) => ({ storeId: s.storeId, spuId: s.spuId }))
  if (pairs.length) emit('append', pairs)
  selected.value.clear()
  open.value = false
}

// 搜索走服务端（防抖），回第 0 页
watch(keyword, () => {
  if (selectedStoreId.value == null) return
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void loadProducts(0), 250)
})

onUnmounted(() => {
  storesCtrl?.abort()
  productsCtrl?.abort()
  if (searchTimer) clearTimeout(searchTimer)
})
</script>

<template>
  <!-- click 与 input 都截停冒泡（缺一不可，照 DistrictPicker）：EditorView 的 .form 挂着 @click="onFormClick"
       和 @input="markDirty"，会重铸幂等 requestId、清掉刚保存态。picker 的脏值只由父组件的 onPickerAppend 显式驱动。 -->
  <div class="sp-picker" @click.stop @input.stop>
    <button
      type="button"
      class="sp-toggle"
      :aria-expanded="open"
      aria-controls="sp-panel"
      data-testid="store-picker-toggle"
      @click="toggle"
    >
      <Icon name="workflow" :size="15" />
      <span>从店铺勾选商品</span>
      <Icon :name="open ? 'chevron-down' : 'chevron-right'" :size="15" />
    </button>

    <div v-if="open" id="sp-panel" class="sp-panel" data-testid="store-picker-panel">
      <div class="sp-cols">
        <!-- 左：店铺列表 -->
        <div class="sp-stores">
          <Skeleton v-if="storesLoading" :rows="4" />
          <div v-else-if="storesErr" class="sp-err" role="alert">
            <span>{{ storesErr }}</span><button type="button" @click="loadStores">重试</button>
          </div>
          <EmptyState v-else-if="!stores.length" icon="workflow" title="该租户暂无可选店铺" hint="可用下方「手动输入」直接填 SPU。" />
          <ul v-else class="sp-store-list">
            <li v-for="s in stores" :key="s.storeId">
              <button
                type="button"
                class="sp-store-row"
                :class="{ active: s.storeId === selectedStoreId }"
                :data-testid="`store-picker-store-${s.storeId}`"
                @click="selectStore(s.storeId)"
              >
                <span class="s-icon"><Icon name="workflow" :size="14" /></span>
                <span class="s-name">{{ storeLabel(s) }}</span>
                <small>{{ s.productCount }} 件</small>
              </button>
            </li>
          </ul>
        </div>

        <!-- 右：该店商品多选 -->
        <div class="sp-products">
          <div class="sp-search">
            <Icon name="search" :size="15" />
            <label class="sr-only" for="sp-kw">搜索商品名</label>
            <input id="sp-kw" v-model="keyword" type="search" placeholder="搜索商品名" :disabled="selectedStoreId == null" />
            <button v-if="keyword" type="button" class="sp-clear" aria-label="清空搜索" @click="keyword = ''">×</button>
          </div>

          <div v-if="selectedStoreId == null" class="muted sp-hint">← 先选左侧店铺</div>
          <template v-else>
            <Skeleton v-if="productsLoading && !products.length" :rows="4" />
            <div v-else-if="productsErr" class="sp-err" role="alert">
              <span>{{ productsErr }}</span><button type="button" @click="loadProducts(page)">重试</button>
            </div>
            <div v-else-if="!total" class="muted sp-hint">{{ keyword ? '没有匹配的商品' : '该店铺暂无商品' }}</div>
            <template v-else>
              <ul class="sp-prod-list">
                <li v-for="p in products" :key="p.spuId">
                  <label class="sp-prod-row" :data-testid="`store-picker-product-${p.spuId}`">
                    <input type="checkbox" :checked="isChecked(p)" @change="toggleProduct(p)" />
                    <span class="p-name">{{ p.spuName || ('SPU ' + p.spuId) }}</span>
                    <b v-if="p.price != null">¥{{ money(p.price) }}</b>
                  </label>
                </li>
              </ul>
              <div class="sp-pager">
                <span class="pg-info">共 {{ total }} 项</span>
                <span class="pg-btns">
                  <button type="button" data-testid="store-picker-prev" aria-label="上一页" :disabled="page <= 0" @click="prev"><Icon name="arrow-left" :size="14" /></button>
                  <span class="pg-num">{{ page + 1 }} / {{ totalPages }}</span>
                  <button type="button" data-testid="store-picker-next" aria-label="下一页" :disabled="page >= totalPages - 1" @click="next"><Icon name="arrow-right" :size="14" /></button>
                </span>
              </div>
            </template>
          </template>
        </div>
      </div>

      <!-- 已选 + 加入绑定 -->
      <div class="sp-foot">
        <div class="sp-selected">
          <span class="sp-count">已选 {{ selectedCount }}</span>
          <span v-for="[k, v] in selected" :key="k" class="sp-chip">
            {{ v.spuName || ('SPU ' + v.spuId) }}
            <button type="button" :aria-label="'移除 ' + (v.spuName || v.spuId)" @click="removeSelected(k)">×</button>
          </span>
        </div>
        <Button variant="primary" size="sm" :disabled="!selectedCount" data-testid="store-picker-confirm" @click="confirm">加入绑定</Button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sp-picker { margin-bottom: var(--sp-2); }
.sp-toggle { display: inline-flex; align-items: center; gap: var(--sp-2); padding: var(--sp-2) var(--sp-3); border: 1px solid var(--border-ctl); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); cursor: pointer; font-size: var(--fs-sm); }
.sp-toggle:hover { background: var(--bg-hover); color: var(--accent); }

.sp-panel { margin-top: var(--sp-2); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); }
.sp-cols { display: grid; grid-template-columns: minmax(150px, 0.8fr) minmax(0, 1.2fr); }
.sp-stores { border-right: 1px solid var(--border); min-width: 0; max-height: 40dvh; overflow-y: auto; }
.sp-products { min-width: 0; max-height: 40dvh; overflow-y: auto; padding: var(--sp-2); }

.sp-store-list { list-style: none; margin: 0; padding: 0; }
.sp-store-row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--sp-2); width: 100%; padding: var(--sp-2) var(--sp-3); border: 0; border-bottom: 1px solid var(--border); background: transparent; color: inherit; text-align: left; cursor: pointer; }
.sp-store-row.active { background: var(--accent-soft); }
.sp-store-row:hover { color: var(--accent); }
.s-icon { display: inline-flex; padding: 5px; border-radius: 6px; background: var(--bg-soft); color: var(--text-faint); }
.s-name { overflow: hidden; font-size: var(--fs-xs); text-overflow: ellipsis; white-space: nowrap; }
.sp-store-row small { color: var(--text-faint); font-size: var(--fs-2xs); font-variant-numeric: tabular-nums; }

.sp-search { display: flex; align-items: center; gap: var(--sp-2); padding: 4px var(--sp-2); border: 1px solid var(--border-ctl); border-radius: var(--radius-sm); background: var(--bg-soft); margin-bottom: var(--sp-2); }
.sp-search:focus-within { outline: 2px solid var(--focus-ring); outline-offset: 1px; }
.sp-search > :deep(svg) { color: var(--text-faint); flex: none; }
/* 16px：iOS Safari 聚焦 <16px 输入会自动放大 → 横向滚动（照 DistrictTree 搜索框） */
.sp-search input { flex: 1; min-width: 0; border: 0; background: transparent; color: var(--text); font-size: var(--fs-lg); }
.sp-search input:focus { outline: none; }
.sp-clear { flex: none; border: 0; background: transparent; color: var(--text-faint); cursor: pointer; font-size: 16px; line-height: 1; }

.sp-prod-list { list-style: none; margin: 0; padding: 0; }
.sp-prod-row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: var(--sp-2); padding: var(--sp-2) 0; border-bottom: 1px solid var(--border); cursor: pointer; }
.sp-prod-row input[type="checkbox"] { width: 16px; height: 16px; accent-color: var(--accent); flex: none; }
.p-name { overflow: hidden; font-size: var(--fs-xs); text-overflow: ellipsis; white-space: nowrap; }
.sp-prod-row b { color: var(--text-soft); font-size: var(--fs-xs); font-variant-numeric: tabular-nums; }

.sp-pager { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-2); min-height: 40px; margin-top: var(--sp-1); }
.pg-info { color: var(--text-faint); font-size: var(--fs-2xs); }
.pg-btns { display: inline-flex; align-items: center; gap: var(--sp-1); }
.pg-num { color: var(--text-soft); font-size: var(--fs-2xs); font-variant-numeric: tabular-nums; }
.pg-btns button { display: inline-flex; align-items: center; justify-content: center; min-width: 30px; padding: 6px; border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text-soft); cursor: pointer; }
.pg-btns button:disabled { opacity: .45; cursor: default; }

.sp-foot { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-3); padding: var(--sp-2) var(--sp-3); border-top: 1px solid var(--border); background: var(--bg-soft); }
.sp-selected { display: flex; flex-wrap: wrap; align-items: center; gap: var(--sp-1); min-width: 0; max-height: 30dvh; overflow-y: auto; }
.sp-count { color: var(--text-soft); font-size: var(--fs-2xs); }
.sp-chip { display: inline-flex; align-items: center; gap: 4px; padding: 2px var(--sp-2); border: 1px solid var(--border-ctl); border-radius: var(--radius-pill); background: var(--bg-elev); font-size: var(--fs-2xs); }
.sp-chip button { border: 0; background: transparent; color: var(--text-faint); cursor: pointer; font-size: 13px; line-height: 1; }

.sp-err { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-2); color: var(--red); font-size: var(--fs-xs); }
.sp-err button { padding: 2px var(--sp-2); border: 1px solid currentColor; border-radius: var(--radius-sm); background: transparent; color: inherit; cursor: pointer; }
.muted { color: var(--text-faint); font-size: var(--fs-xs); }
.sp-hint { padding: var(--sp-3); }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0; }

/* ≤768：两栏改上下堆叠（照移动端结论，避开窄屏并排撑破） */
@media (max-width: 767px) {
  .sp-cols { grid-template-columns: 1fr; }
  .sp-stores { border-right: 0; border-bottom: 1px solid var(--border); max-height: 30dvh; }
}
/* 触屏：命中区撑到 44px（EditorView / 本组件全靠自补，全局兜底压不过 scoped） */
@media (pointer: coarse) {
  .sp-toggle, .sp-store-row, .sp-prod-row { min-height: var(--touch-min); }
  .pg-btns button, .sp-clear, .sp-chip button { min-width: var(--touch-min); min-height: var(--touch-min); }
}
</style>
