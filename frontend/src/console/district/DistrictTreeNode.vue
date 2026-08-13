<script setup lang="ts">
/**
 * 树节点（Vue SFC 自引用递归，仿 `ConditionGroup.vue`）。一行 = 展开三角(仅有子节点) + 方形勾选框 + 名字 + 半选分数徽标。
 *
 * <b>痛点①的解法在结构里</b>：叶子行（区县，占绝大多数）**没有三角**；三角是独立的**圆形**按钮、
 * 勾选框是**方形**，两者形状 + 各自 hover/focus 底色先于点击就能分辨「点哪个是选中、点哪个是展开」。
 *
 * <b>纯派生、绝不写库</b>：三态(`checkStateOf`)、分数(`selectedLeafCountOf/leafCountOf`)全是对 `selected` 的**只读**推导；
 * 勾一个节点只 `emit('toggle', code)` 冒泡到 DistrictTree 统一走 `toggleNode`，本组件不碰 `selected` 数组。
 *
 * <b>子级惰性挂载</b>：`v-if="open && shownKids.length"` —— 折叠时子节点**根本不进 DOM**（性能，且 e2e
 * `count([data-testid^=district-opt-])===34` 依赖此：CSS 折叠会让 count 静默变大）。
 */
import { computed } from 'vue'
import type { District } from '@/shared/types'
import { checkStateOf, leafCountOf, selectedLeafCountOf, childrenOf, type DistrictIndex } from './districtLogic'
import Icon from '@/shared/ui/Icon.vue'

const props = defineProps<{
  node: District
  index: DistrictIndex
  selected: string[]
  /** 生效展开集：常规态=用户手动展开；搜索/只看已选态由 DistrictTree 换成对应的强制展开集。 */
  expanded: Set<string>
  /** 非 null 时只渲染在集合里的子节点（搜索/只看已选的过滤）；null=渲染全部子节点。 */
  visibleFilter?: Set<string> | null
  /** 命中集（搜索态高亮用）。 */
  matches?: Set<string> | null
  /** 已达 146 上限：未选节点禁用。 */
  full?: boolean
  depth: number
}>()
const emit = defineEmits<{ toggle: [code: string]; 'toggle-expand': [code: string] }>()

const state = computed(() => checkStateOf(props.index, props.selected, props.node.code))
const checked = computed(() => state.value === 'checked')
const indeterminate = computed(() => state.value === 'indeterminate')
const selfSelected = computed(() => props.selected.includes(props.node.code))
/** 被祖先覆盖（整省已选时的子级）：显示为勾中但**不可单独取消**——非排除项语义。 */
const covered = computed(() => checked.value && !selfSelected.value)
const disabled = computed(() => covered.value || (!!props.full && state.value === 'unchecked'))

const kids = computed(() => childrenOf(props.index, props.node.code))
const hasChildren = computed(() => kids.value.length > 0)
const shownKids = computed(() =>
  props.visibleFilter ? kids.value.filter((k) => props.visibleFilter!.has(k.code)) : kids.value)
const open = computed(() => props.expanded.has(props.node.code))
const isMatch = computed(() => props.matches?.has(props.node.code) ?? false)

const leafCount = computed(() => leafCountOf(props.index, props.node.code))
const selCount = computed(() => selectedLeafCountOf(props.index, props.selected, props.node.code))
</script>

<template>
  <li class="node">
    <div class="row" :class="{ checked, indeterminate, covered }" :style="{ '--depth': depth }">
      <button v-if="hasChildren" type="button" class="disc"
              :data-testid="'district-expand-' + node.code"
              :aria-expanded="open" :aria-controls="'d-' + node.code"
              :aria-label="(open ? '收起 ' : '展开 ') + node.name"
              @click="emit('toggle-expand', node.code)">
        <Icon name="chevron-right" :size="14" :class="{ open }" />
      </button>
      <span v-else class="disc-spacer" aria-hidden="true" />

      <label class="pick" :class="{ dim: disabled }">
        <input type="checkbox" :checked="checked" :indeterminate="indeterminate" :disabled="disabled"
               :data-testid="'district-opt-' + node.code" @change="emit('toggle', node.code)" />
        <span class="nm" :class="{ hit: isMatch }">{{ node.shortName }}</span>
      </label>

      <span v-if="indeterminate" class="frac mono" :title="node.name + ' 已选 ' + selCount + ' / ' + leafCount + ' 个区县'">
        {{ selCount }}/{{ leafCount }}
      </span>
    </div>

    <ul v-if="hasChildren && open && shownKids.length" :id="'d-' + node.code" class="children">
      <DistrictTreeNode v-for="k in shownKids" :key="k.code"
                        :node="k" :index="index" :selected="selected" :expanded="expanded"
                        :visible-filter="visibleFilter" :matches="matches" :full="full" :depth="depth + 1"
                        @toggle="emit('toggle', $event)" @toggle-expand="emit('toggle-expand', $event)" />
    </ul>
  </li>
</template>

<style scoped>
.node { list-style: none; }
.children { list-style: none; margin: 0; padding: 0; }

.row {
  display: flex; align-items: center; gap: var(--sp-1); min-width: 0;
  /* 缩进 = 基础 + depth×一步；depth 由父层按祖先链长度累加，不看 level。min-width:0 是横向溢出防线。 */
  padding: 4px var(--sp-2) 4px calc(var(--sp-2) + var(--sp-4) * var(--depth));
  border-radius: var(--radius-sm);
}
.row.checked { background: var(--accent-soft); }
/* 半选行底：照抄 DemoNav .group.current 的 46%-mix「次级激活」配方，非自造。 */
.row.indeterminate { background: color-mix(in srgb, var(--accent-soft) 46%, transparent); }
@media (hover: hover) {
  /* 触屏点过会「粘住」hover 被误读成选中，故只在真 hover 设备开。 */
  .row:hover { background: var(--bg-hover); }
}

/* 展开三角：圆形命中容器（vs 勾选框的方形），几何区分「展开 vs 选中」。 */
.disc {
  display: inline-flex; align-items: center; justify-content: center; flex: none;
  width: 22px; height: 22px; border: 0; border-radius: 50%;
  background: transparent; color: var(--text-faint); cursor: pointer;
}
.disc:hover { background: var(--bg-hover); color: var(--text); }
.disc:focus-visible { outline: 0; box-shadow: var(--focus-ring); }
.disc .icon { transition: transform var(--dur-mid) var(--ease-out); }
.disc .icon.open { transform: rotate(90deg); }
.disc-spacer { flex: none; width: 22px; }

.pick { display: flex; align-items: center; gap: var(--sp-2); min-width: 0; flex: 1; cursor: pointer; }
.pick.dim { color: var(--text-faint); cursor: not-allowed; }
.pick input { flex: none; width: 16px; height: 16px; accent-color: var(--accent); cursor: inherit; }
.pick input:focus-visible { outline: 0; box-shadow: var(--focus-ring); border-radius: 3px; }

.nm { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: var(--fs-sm); }
.row.checked .nm { color: var(--accent); font-weight: var(--fw-semibold); }
/* 搜索命中高亮：拼音/首字母命中时名字里没有可高亮的子串，故整名标记而非 <mark> 局部。 */
.nm.hit { background: color-mix(in srgb, var(--accent) 24%, transparent); border-radius: 3px; padding: 0 3px; }

.frac {
  flex: none; font-size: var(--fs-2xs); color: var(--accent);
  background: var(--accent-ink); padding: 1px 6px; border-radius: var(--radius-pill);
}

@media (pointer: coarse) {
  /* 全局 (pointer:coarse) 兜底排除 checkbox/radio 且压不过 scoped，故行/三角各自补 44px 命中区。 */
  .pick { min-height: var(--touch-min); }
  .disc { width: var(--touch-min); height: var(--touch-min); }
  .disc-spacer { width: var(--touch-min); }
}
</style>
