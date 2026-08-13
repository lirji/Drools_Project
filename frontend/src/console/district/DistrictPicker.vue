<script setup lang="ts">
/**
 * 投放地域选择器（壳层）：计数 + 触发展开的树面板 + **完整、按省分组的已选清单** + 字典降级逃生门。
 *
 * <b>已选清单为什么按省分组、而不是一大片 chips</b>：痛点④「选多了看不清」。分组后「广东省: 深圳 珠海 / 江苏省: …」
 * 结构一眼可核对，且未知/已撤销码（树里没有节点、无处安放）单列成「未知代码」小节——用**结构化**而非删除来解。
 *
 * <b>click 与 input 都不冒泡（缺一不可）</b>：EditorView 的 `.form` 上挂着 `@click="onFormClick"` **和**
 * `@input="markDirty"`，而 markDirty 会重铸幂等 requestId、清掉刚保存的成功态。只截 click 的话，「保存成功后
 * 点开地域看一眼」不再出问题，但**在搜索框里打一个字**照样出问题。所以两个都截在根上，脏值只由 `codes`
 * 的 v-model setter 驱动（EditorView 的 districtCodes computed 里显式 markDirty）——改了才算改。
 */
import { computed, ref } from 'vue'
import type { District } from '@/shared/types'
import DistrictTree from './DistrictTree.vue'
import { buildIndex, pathOf, labelOf, isKnown, budgetOf, removeCode, ancestorsOf, parseCodes, toCsv, MAX_DISTRICTS } from './districtLogic'
import Badge from '@/shared/ui/Badge.vue'
import Banner from '@/shared/ui/Banner.vue'
import Icon from '@/shared/ui/Icon.vue'

const props = defineProps<{
  districts: District[] | null
  loading?: boolean
  failed?: boolean
}>()
const codes = defineModel<string[]>({ required: true })

const open = ref(false)

const index = computed(() => buildIndex(props.districts))
const budget = computed(() => budgetOf(codes.value))
const unknownCount = computed(() =>
  props.districts ? codes.value.filter((c) => !isKnown(index.value, c)).length : 0)

/**
 * 完整已选清单，按省分组（未知/已撤销码单列）。分组键 = 顶层祖先（省）；`whole` = 该项本身就是省（整省选择）。
 * 保持 `codes` 的原始顺序落到各组，不擅自重排。
 */
const groups = computed(() => {
  const idx = index.value
  const map = new Map<string, { code: string; name: string; items: { code: string; label: string; whole: boolean }[] }>()
  const unknown: string[] = []
  for (const code of codes.value) {
    if (props.districts && !isKnown(idx, code)) { unknown.push(code); continue }
    const anc = ancestorsOf(idx, code)
    const pcode = anc.length ? anc[anc.length - 1] : code
    let g = map.get(pcode)
    if (!g) { g = { code: pcode, name: labelOf(idx, pcode), items: [] }; map.set(pcode, g) }
    g.items.push({ code, label: labelOf(idx, code), whole: code === pcode })
  }
  return { list: [...map.values()], unknown }
})

/**
 * 字典拿不到时的逃生门：仍能编辑原始 CSV，否则字典一挂运营连改都改不了。
 * `rawDraft` 这一层的必要性：`v-model` 每敲一个字符都会走 set→get，若 get 直接返回 `toCsv(parseCodes(输入))`，
 * 敲到 `440300,` 时尾随逗号会被吞掉、第二个码永远输不进去。故打字期间原样保留字符串，失焦(`@blur`)再规范化。
 */
const rawDraft = ref<string | null>(null)
const rawCsv = computed({
  get: () => rawDraft.value ?? toCsv(codes.value),
  set: (v: string) => { rawDraft.value = v; codes.value = parseCodes(v) },
})

function drop(code: string): void {
  codes.value = removeCode(codes.value, code)
}
function clearAll(): void {
  codes.value = []
}
</script>

<template>
  <div class="dp" data-testid="district-picker" @click.stop @input.stop>
    <div class="head">
      <button type="button" class="toggle" data-testid="district-toggle"
              :aria-expanded="open" @click="open = !open">
        <Icon :name="open ? 'chevron-down' : 'chevron-right'" :size="12" />
        {{ open ? '收起' : '选择地域' }}
      </button>
      <span class="count" data-testid="district-count">
        已选 {{ budget.used }} / {{ MAX_DISTRICTS }}
      </span>
      <button v-if="codes.length" type="button" class="link" data-testid="district-clear"
              @click="clearAll">清空</button>
    </div>

    <Banner v-if="failed" kind="warn" data-testid="district-warning">
      行政区字典未就绪，暂时只能按代码手工填写；已选的地域会原样保留。
    </Banner>

    <p v-if="budget.full" class="warn" role="status" data-testid="district-limit">
      已选满 {{ MAX_DISTRICTS }} 个（投放地域按编码存进一列，上限 1024 字符）。取消部分选择后才能继续。
    </p>
    <p v-if="unknownCount" class="warn" data-testid="district-unknown">
      有 {{ unknownCount }} 个代码不在当前行政区字典中，可能已撤销（例如 2025-11 重庆江北区/渝北区已并入两江新区）。
      它们会<b>原样保留</b>，见下方「未知代码」，不会被自动删除。
    </p>

    <DistrictTree v-if="open" :districts="districts" :selected="codes" :loading="loading"
                  @update:selected="codes = $event" />

    <div v-if="codes.length" class="sel" data-testid="district-chips">
      <div v-for="g in groups.list" :key="g.code" class="grp">
        <span class="grp-name">{{ g.name }}</span>
        <span v-for="it in g.items" :key="it.code" class="chip" :class="{ whole: it.whole }"
              :title="pathOf(index, it.code)">
          {{ it.whole ? '全省' : it.label }}
          <button type="button" class="x" :data-testid="'district-chip-x-' + it.code"
                  :aria-label="'移除 ' + pathOf(index, it.code)" @click="drop(it.code)">
            <Icon name="x" :size="10" />
          </button>
        </span>
      </div>
      <div v-if="groups.unknown.length" class="grp unknown">
        <span class="grp-name"><Badge kind="warn" shape="triangle">?</Badge> 未知代码</span>
        <span v-for="c in groups.unknown" :key="c" class="chip unknown" :title="c + '（未知 / 已撤销代码）'">
          {{ c }}
          <button type="button" class="x" :data-testid="'district-chip-x-' + c"
                  :aria-label="'移除 ' + c" @click="drop(c)"><Icon name="x" :size="10" /></button>
        </span>
      </div>
    </div>
    <p v-else class="hint" data-testid="district-empty-hint">未选择任何地域（等同不投放，保存前请至少选一个或切回「全国」）</p>

    <label v-if="failed || (open && !districts)" class="raw">
      <span>地域代码（逗号分隔）</span>
      <input v-model="rawCsv" data-testid="district-raw" placeholder="如 440300,110000"
             @blur="rawDraft = null" />
    </label>
  </div>
</template>

<style scoped>
.dp { display: flex; flex-direction: column; gap: var(--sp-2); min-width: 0; }
.head { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; }
.toggle {
  display: inline-flex; align-items: center; gap: 4px;
  border: 1px solid var(--border-ctl); border-radius: var(--radius-sm);
  background: var(--bg-elev); color: var(--text); cursor: pointer;
  padding: var(--sp-1) var(--sp-2); font-size: var(--fs-sm); min-height: 38px;
}
.count { color: var(--text-faint); font-size: var(--fs-xs); }
.link { border: 0; background: transparent; color: var(--accent); cursor: pointer; font-size: var(--fs-xs); }
.warn { color: var(--warn); font-size: var(--fs-xs); margin: 0; }
.hint { color: var(--text-faint); font-size: var(--fs-xs); margin: 0; }

/* 完整已选清单：卡片化、按省分组，与树同一套 --bg-elev/--border 视觉，消除现状零碎感（痛点②）。 */
.sel {
  display: flex; flex-direction: column; gap: var(--sp-2);
  max-height: 30dvh; overflow-y: auto;
  border: 1px solid var(--border); border-radius: var(--radius-sm);
  background: var(--bg-elev); padding: var(--sp-2);
}
.grp { display: flex; flex-wrap: wrap; align-items: center; gap: var(--sp-1); min-width: 0; }
.grp-name {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: var(--fs-xs); font-weight: var(--fw-semibold); color: var(--text-soft);
  margin-right: var(--sp-1);
}
.grp.unknown .grp-name { color: var(--warn); }
.chip {
  display: inline-flex; align-items: center; gap: 4px;
  border: 1px solid var(--border-ctl); border-radius: var(--radius-pill);
  background: var(--bg-soft); color: var(--text);
  padding: 2px var(--sp-2); font-size: 12.5px; max-width: 100%;
}
.chip.whole { border-color: var(--accent-line); color: var(--accent); }
.chip.unknown { border-color: var(--warn); }
.x { border: 0; background: transparent; color: var(--text-faint); cursor: pointer; padding: 0 2px; line-height: 1; }

.raw { display: flex; flex-direction: column; gap: 2px; }
.raw input {
  border: 1px solid var(--border-ctl); border-radius: var(--radius-sm);
  background: var(--bg-elev); color: var(--text); padding: var(--sp-1) var(--sp-2); min-height: 38px;
}

@media (pointer: coarse) {
  .toggle, .raw input { min-height: var(--touch-min); }
  /* chip 的 × 只有 10px 图标，命中区必须自己撑到 44——全局兜底压不过 scoped 样式。 */
  .x, .link { min-height: var(--touch-min); min-width: var(--touch-min); }
  .chip { min-height: var(--touch-min); }
}
</style>
