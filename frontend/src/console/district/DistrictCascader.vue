<script setup lang="ts">
/**
 * 级联面板本体：搜索 + 逐级列表 + 勾选。**不含已选 chips 与触发器**（那是 DistrictPicker 的事）。
 *
 * 形态随宽度切：≥768 三栏并排；<768 只显示当前这一栏 + 面包屑回退（3212 条数据在 390px 上
 * 并排三栏必横向溢出，而编辑页是 e2e 量溢出的地方）。切换靠 CSS 而不是 JS 断点判断——
 * 页面自造断点会与壳层（AppLayout 抽屉 767 / SidePanel sheet 1023）错位，正典四档见 tokens.css:31。
 */
import { computed, ref } from 'vue'
import type { District } from '@/shared/types'
import {
  buildIndex, childrenOf, search, addCode, removeCode, ancestorsOf, MAX_DISTRICTS,
} from './districtLogic'
import EmptyState from '@/shared/ui/EmptyState.vue'
import Skeleton from '@/shared/ui/Skeleton.vue'
import Icon from '@/shared/ui/Icon.vue'

const props = defineProps<{
  districts: District[] | null
  selected: string[]
  loading?: boolean
}>()
const emit = defineEmits<{ 'update:selected': [v: string[]] }>()

const index = computed(() => buildIndex(props.districts))
const q = ref('')
/** 下钻路径：[省码] 或 [省码, 市码]。空 = 停在第一栏。 */
const drill = ref<string[]>([])

const SEARCH_LIMIT = 50
const hits = computed(() => search(props.districts, q.value, SEARCH_LIMIT))
const searching = computed(() => q.value.trim().length > 0)

/** 逐级列表。第 0 栏恒为省级；后面几栏由下钻路径决定。 */
const columns = computed<District[][]>(() => {
  const cols: District[][] = [childrenOf(index.value, null)]
  for (const code of drill.value) {
    const kids = childrenOf(index.value, code)
    if (!kids.length) break // 直辖市的区、直筒子市：没有下一级就不开新栏
    cols.push(kids)
  }
  return cols
})

const crumbs = computed(() =>
  drill.value.map((c) => ({ code: c, name: index.value.byCode.get(c)?.shortName || c })))

const full = computed(() => props.selected.length >= MAX_DISTRICTS)

function isSelected(code: string): boolean {
  return props.selected.includes(code)
}
/** 祖先已被选中 → 这一项是被"包含"的，不能也不必再单独勾。 */
function coveredByAncestor(code: string): boolean {
  return ancestorsOf(index.value, code).some((a) => props.selected.includes(a))
}
function hasChildren(code: string): boolean {
  return childrenOf(index.value, code).length > 0
}

function toggle(code: string): void {
  if (isSelected(code)) {
    emit('update:selected', removeCode(props.selected, code))
    return
  }
  if (coveredByAncestor(code) || full.value) return
  emit('update:selected', addCode(index.value, props.selected, code))
}

function openLevel(depth: number, code: string): void {
  drill.value = [...drill.value.slice(0, depth), code]
}
function backTo(depth: number): void {
  drill.value = drill.value.slice(0, depth)
}
</script>

<template>
  <div class="casc" data-testid="district-cascader">
    <div class="search">
      <Icon name="search" :size="14" />
      <label class="sr-only" for="district-q">搜索行政区</label>
      <input id="district-q" v-model="q" type="search" data-testid="district-search"
             placeholder="搜索省/市/区县，支持简称与拼音（如 南山 / nanshan）" />
      <button v-if="q" type="button" class="clear" data-testid="district-search-clear"
              aria-label="清空搜索" @click="q = ''"><Icon name="x" :size="12" /></button>
    </div>

    <Skeleton v-if="loading" :rows="6" />

    <!-- 搜索结果：跨级平铺，命中项直接显示完整路径，省得再下钻确认是哪个「鼓楼区」 -->
    <div v-else-if="searching" class="panel">
      <EmptyState v-if="!hits.length" icon="search" title="没有匹配的行政区"
                  hint="试试简称或拼音，如「南山」「nanshan」" />
      <template v-else>
        <ul class="col wide">
          <li v-for="dst in hits" :key="dst.code">
            <label class="row" :class="{ dim: coveredByAncestor(dst.code) }">
              <input type="checkbox" :checked="isSelected(dst.code)"
                     :disabled="coveredByAncestor(dst.code) || (full && !isSelected(dst.code))"
                     :data-testid="'district-hit-' + dst.code"
                     @change="toggle(dst.code)" />
              <span class="nm">{{ dst.name }}</span>
              <small class="path">{{ dst.parent ? (index.byCode.get(dst.parent)?.shortName || '') : '' }}</small>
            </label>
          </li>
        </ul>
        <p v-if="hits.length >= SEARCH_LIMIT" class="trunc" data-testid="district-search-trunc">
          只显示前 {{ SEARCH_LIMIT }} 条，输入更精确的关键词以缩小范围
        </p>
      </template>
    </div>

    <div v-else class="panel">
      <!-- 面包屑：<768 时它是唯一的回退路径；≥768 时它只是当前位置的提示 -->
      <nav v-if="crumbs.length" class="crumbs" aria-label="已选层级">
        <button type="button" class="crumb" data-testid="district-crumb-root" @click="backTo(0)">全国</button>
        <template v-for="(c, i) in crumbs" :key="c.code">
          <Icon name="chevron-right" :size="12" />
          <button type="button" class="crumb" :data-testid="'district-crumb-' + c.code"
                  @click="backTo(i + 1)">{{ c.name }}</button>
        </template>
      </nav>

      <!-- 轨道数跟着实际栏数走：恒开 3 条时，未下钻的省级列表右边会空掉整整 2/3。 -->
      <div class="cols" :style="{ '--cols': columns.length }">
        <ul v-for="(col, depth) in columns" :key="depth" class="col"
            :class="{ 'is-current': depth === columns.length - 1 }">
          <li v-for="dst in col" :key="dst.code">
            <label class="row" :class="{ dim: coveredByAncestor(dst.code), active: drill[depth] === dst.code }">
              <input type="checkbox" :checked="isSelected(dst.code)"
                     :disabled="coveredByAncestor(dst.code) || (full && !isSelected(dst.code))"
                     :data-testid="'district-opt-' + dst.code"
                     @change="toggle(dst.code)" />
              <span class="nm">{{ dst.shortName }}</span>
              <button v-if="hasChildren(dst.code)" type="button" class="into"
                      :data-testid="'district-into-' + dst.code"
                      :aria-label="'展开 ' + dst.name" @click.prevent="openLevel(depth, dst.code)">
                <Icon name="chevron-right" :size="12" />
              </button>
            </label>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<style scoped>
.casc { display: flex; flex-direction: column; gap: var(--sp-2); min-width: 0; }

.search {
  display: flex; align-items: center; gap: var(--sp-1);
  border: 1px solid var(--border-ctl); border-radius: var(--radius-sm);
  background: var(--bg-elev); padding: 0 var(--sp-2); min-height: 38px;
}
.search:focus-within { border-color: var(--accent); box-shadow: var(--focus-ring); }
/* 16px 不是审美：iOS Safari 聚焦 <16px 的输入会自动放大页面，直接制造横向滚动，
   而编辑页正是 e2e 量「零横向溢出」的地方。ListView 的搜索框用的是 13px，这里刻意偏离。 */
.search input {
  flex: 1; min-width: 0; border: 0; outline: 0; background: transparent;
  color: var(--text); font-size: var(--fs-lg); padding: var(--sp-1) 0;
}
.clear { border: 0; background: transparent; color: var(--text-faint); cursor: pointer; padding: var(--sp-1); }

.panel { min-width: 0; }
.crumbs { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; margin-bottom: var(--sp-1); }
.crumb {
  border: 0; background: transparent; color: var(--accent);
  font-size: var(--fs-xs); cursor: pointer; padding: 2px 4px;
}

/* minmax(0,1fr) 是溢出防线：grid 子项默认 min-width:auto，长地名（「克孜勒苏柯尔克孜自治州」）
   会把栅格顶宽，撑破 768 的横向溢出断言。禁止在这里写 min-width: 200px 之类的硬值。 */
/* 轨道数 = 实际栏数（恒开 3 条会让未下钻时右边空掉 2/3）；每条给**定宽上限**而不是 1fr，
   否则只有省级一栏时它会被拉到 800px 宽，地名贴左、箭头贴右，中间一大片空白。
   `minmax(0, 260px)` 的下限是 0，窄容器里轨道会自己缩，撑不破横向（tablet/phone 的溢出断言依赖这点）。 */
.cols { display: grid; grid-template-columns: repeat(var(--cols, 1), minmax(0, 260px)); gap: var(--sp-2); min-width: 0; }
.col {
  list-style: none; margin: 0; padding: 0; min-width: 0;
  max-height: 260px; overflow-y: auto;
  border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev);
}
.col.wide { grid-column: 1 / -1; max-height: 320px; }

.row {
  display: flex; align-items: center; gap: var(--sp-1);
  padding: 6px var(--sp-2); cursor: pointer; min-width: 0;
}
.row .nm { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: var(--fs-sm); }
.row .path { color: var(--text-faint); font-size: var(--fs-2xs); }
.row.dim { color: var(--text-faint); }
.row.active { background: var(--bg-hover); }
.into { border: 0; background: transparent; color: var(--text-faint); cursor: pointer; padding: 2px; }
.trunc { color: var(--text-faint); font-size: var(--fs-xs); margin: var(--sp-1) 0 0; }

.sr-only {
  position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px;
  overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}

/* 触屏上点过的项会「粘住」hover 态；多选场景里那会被读成「已选中」，是语义 bug 不只是观感。 */
@media (hover: hover) {
  .row:hover { background: var(--bg-hover); }
}

/* <768：三栏并排在 390px 上必横向溢出 → 只留当前这一栏，靠面包屑回退。 */
@media (max-width: 767px) {
  .cols { grid-template-columns: 1fr; } /* 压过上面的 var(--cols)：手机只显示当前这一栏 */
  .col:not(.is-current) { display: none; }
  .col { max-height: 46dvh; }
}

/* tokens.css:373-388 明说全局那条 (pointer:coarse) 兜底压不过组件 scoped 样式
   （button 是 0-0-1，scoped 的 .row 带 [data-v-*] 至少 0-2-0），所以每个组件必须自己补一条。 */
@media (pointer: coarse) {
  .row { min-height: var(--touch-min); }
  .into, .clear, .crumb { min-height: var(--touch-min); min-width: var(--touch-min); }
  .search { min-height: var(--touch-min); }
}
</style>
