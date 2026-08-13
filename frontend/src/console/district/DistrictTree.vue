<script setup lang="ts">
/**
 * 树体：搜索 + 省市区树 + 「只看已选」「折叠全部」两个视图开关。**不含已选清单与触发器**（那是 DistrictPicker 的事）。
 * 取代原 DistrictCascader 的「miller 三栏 + 面包屑下钻」——树天然纵向、多分支可同时展开，一套形态吃下所有断点。
 *
 * <b>三种视图（互斥优先级：搜索 &gt; 只看已选 &gt; 常规）</b>：
 * - 搜索(q 非空)：树内就地过滤——只渲染命中 ∪ 祖先，命中分支强制展开、`<mark>` 高亮；命中仍是 district-opt 节点。
 * - 只看已选：纯渲染过滤，只留 selected ∪ 其祖先并强制展开；**不 mutate 用户手动 `expanded`**，关掉即恢复。
 * - 常规：默认折叠 34 省，`expanded:Set` 驱动惰性展开。
 *
 * <b>展开集用 `v-if` 惰性挂载子级</b>（见 DistrictTreeNode）：折叠时子节点不进 DOM——性能，且守住 e2e 的省级 count===34。
 */
import { computed, ref, watch } from 'vue'
import type { District } from '@/shared/types'
import {
  buildIndex, searchScope, defaultExpandedOf, toggleNode, MAX_DISTRICTS,
} from './districtLogic'
import DistrictTreeNode from './DistrictTreeNode.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'
import Skeleton from '@/shared/ui/Skeleton.vue'
import Icon from '@/shared/ui/Icon.vue'

const props = defineProps<{
  districts: District[] | null
  selected: string[]
  loading?: boolean
}>()
const emit = defineEmits<{ 'update:selected': [v: string[]] }>()

const SEARCH_LIMIT = 80

const index = computed(() => buildIndex(props.districts))
const q = ref('')
const selectedOnly = ref(false)
/** 用户手动展开集（仅常规态用；搜索/只看已选态用各自算出的强制展开集）。 */
const expanded = ref<Set<string>>(new Set())

const searching = computed(() => q.value.trim().length > 0)
const full = computed(() => props.selected.length >= MAX_DISTRICTS)

/** 编辑回读：字典首次就绪时把树展开到已选路径（只做一次，之后交给用户手动）。 */
const seeded = ref(false)
watch(() => props.districts, (d) => {
  if (!seeded.value && d && d.length) {
    expanded.value = defaultExpandedOf(index.value, props.selected)
    seeded.value = true
  }
}, { immediate: true })

const view = computed(() => {
  if (searching.value) {
    const sc = searchScope(index.value, props.districts, q.value, SEARCH_LIMIT)
    return {
      roots: index.value.roots.filter((r) => sc.visible.has(r.code)),
      expanded: sc.expand, filter: sc.visible, matches: sc.matches, truncated: sc.truncated,
    }
  }
  if (selectedOnly.value) {
    const anc = defaultExpandedOf(index.value, props.selected)
    const vis = new Set<string>([...props.selected, ...anc])
    return {
      roots: index.value.roots.filter((r) => vis.has(r.code)),
      expanded: anc, filter: vis, matches: null, truncated: false,
    }
  }
  return {
    roots: index.value.roots, expanded: expanded.value, filter: null, matches: null, truncated: false,
  }
})

const canCollapseAll = computed(() => !searching.value && !selectedOnly.value && expanded.value.size > 0)

function onToggle(code: string): void {
  emit('update:selected', toggleNode(index.value, props.selected, code, full.value))
}
function onToggleExpand(code: string): void {
  const s = new Set(expanded.value)
  s.has(code) ? s.delete(code) : s.add(code)
  expanded.value = s
}
function collapseAll(): void {
  expanded.value = new Set()
}
</script>

<template>
  <div class="tree-panel" data-testid="district-tree">
    <div class="toolbar">
      <div class="search">
        <Icon name="search" :size="14" />
        <label class="sr-only" for="district-q">搜索行政区</label>
        <input id="district-q" v-model="q" type="search" data-testid="district-search"
               placeholder="搜索省/市/区县，支持简称与拼音（如 南山 / nanshan）" />
        <button v-if="q" type="button" class="clear" data-testid="district-search-clear"
                aria-label="清空搜索" @click="q = ''"><Icon name="x" :size="12" /></button>
      </div>
      <div class="switches">
        <button type="button" class="sw" data-testid="district-selected-only"
                :class="{ on: selectedOnly }" :aria-pressed="selectedOnly"
                @click="selectedOnly = !selectedOnly">
          <Icon name="check" :size="12" /> 只看已选
        </button>
        <button v-if="canCollapseAll" type="button" class="sw" data-testid="district-collapse-all"
                @click="collapseAll"><Icon name="chevron-down" :size="12" /> 折叠全部</button>
      </div>
    </div>

    <Skeleton v-if="loading" :rows="6" />

    <template v-else-if="districts">
      <EmptyState v-if="searching && !view.roots.length" icon="search" title="没有匹配的行政区"
                  hint="试试简称或拼音，如「南山」「nanshan」" />
      <EmptyState v-else-if="selectedOnly && !view.roots.length" icon="inbox" title="没有已选的行政区"
                  hint="在字典里勾选，或关掉「只看已选」浏览全部" />
      <template v-else>
        <ul class="tree" aria-label="行政区划">
          <DistrictTreeNode v-for="r in view.roots" :key="r.code"
                            :node="r" :index="index" :selected="selected" :expanded="view.expanded"
                            :visible-filter="view.filter" :matches="view.matches" :full="full" :depth="0"
                            @toggle="onToggle" @toggle-expand="onToggleExpand" />
        </ul>
        <p v-if="view.truncated" class="trunc" data-testid="district-search-trunc">
          命中较多，只显示前 {{ SEARCH_LIMIT }} 条，输入更精确的关键词以缩小范围
        </p>
      </template>
    </template>
  </div>
</template>

<style scoped>
.tree-panel { display: flex; flex-direction: column; gap: var(--sp-2); min-width: 0; }

.toolbar { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.search {
  display: flex; align-items: center; gap: var(--sp-1); flex: 1; min-width: 180px;
  border: 1px solid var(--border-ctl); border-radius: var(--radius-sm);
  background: var(--bg-elev); padding: 0 var(--sp-2); min-height: 38px;
}
.search:focus-within { border-color: var(--accent); box-shadow: var(--focus-ring); }
/* 16px 不是审美：iOS Safari 聚焦 <16px 输入会自动放大页面 → 横向滚动，而编辑页正是 e2e 量零溢出的地方。 */
.search input {
  flex: 1; min-width: 0; border: 0; outline: 0; background: transparent;
  color: var(--text); font-size: var(--fs-lg); padding: var(--sp-1) 0;
}
.clear { border: 0; background: transparent; color: var(--text-faint); cursor: pointer; padding: var(--sp-1); }

.switches { display: flex; align-items: center; gap: var(--sp-1); }
.sw {
  display: inline-flex; align-items: center; gap: 4px;
  border: 1px solid var(--border-ctl); border-radius: var(--radius-sm);
  background: var(--bg-elev); color: var(--text-soft); cursor: pointer;
  padding: var(--sp-1) var(--sp-2); font-size: var(--fs-xs); min-height: 34px;
}
.sw:hover { background: var(--bg-hover); }
.sw.on { background: var(--accent-soft); color: var(--accent); border-color: var(--accent-line); }
.sw:focus-visible { outline: 0; box-shadow: var(--focus-ring); }

.tree {
  list-style: none; margin: 0; padding: var(--sp-1);
  max-height: min(50dvh, 480px); overflow-y: auto;
  border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev);
}
.trunc { color: var(--text-faint); font-size: var(--fs-xs); margin: var(--sp-1) 0 0; }

.sr-only {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}

@media (pointer: coarse) {
  .search { min-height: var(--touch-min); }
  .sw, .clear { min-height: var(--touch-min); }
  .tree { max-height: 52dvh; }
}
</style>
