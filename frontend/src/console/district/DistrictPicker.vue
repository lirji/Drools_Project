<script setup lang="ts">
/**
 * 投放地域选择器：已选摘要 + 可删 chips + 就地展开的级联面板。
 *
 * <b>为什么是表单内联展开而不是 SidePanel</b>：SidePanel 的 push 阈值是 **1280**（不是 1024），
 * overlay 档宽度 `min(94vw,458px)` 装不下并排三栏；≥1280 还会退化成非模态 sticky 块
 * （那一档它不加滚动锁、不做焦点陷阱）。而且全屏 sheet 会盖住 submit，
 * 直接让 e2e-tablet/phone-smoke 的「填表→提交」超时。内联展开一次解掉这四件事。
 *
 * <b>click 与 input 都不冒泡</b>：EditorView 的 `.form` 上挂着 `@click="onFormClick"`
 * **和** `@input="markDirty"`（:623），而 markDirty 会重铸幂等 requestId 并清掉刚保存的成功态。
 * 只截 click 的话，「保存成功后点开地域看一眼」不再出问题，但**在搜索框里打一个字**照样出问题：
 * 成功卡消失、离开时弹未保存确认，而运营只是想找一下「南山」在哪。
 * 所以两个都截在根上，脏值只由 `codes` 的 v-model setter 驱动——改了才算改。
 */
import { computed, ref } from 'vue'
import type { District } from '@/shared/types'
import DistrictCascader from './DistrictCascader.vue'
import { buildIndex, pathOf, labelOf, isKnown, budgetOf, removeCode, parseCodes, toCsv, MAX_DISTRICTS } from './districtLogic'
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
const showAllChips = ref(false)
const CHIP_PREVIEW = 8

const index = computed(() => buildIndex(props.districts))
const budget = computed(() => budgetOf(codes.value))
const unknownCount = computed(() =>
  props.districts ? codes.value.filter((c) => !isKnown(index.value, c)).length : 0)
const visibleChips = computed(() =>
  showAllChips.value ? codes.value : codes.value.slice(0, CHIP_PREVIEW))

/**
 * 字典拿不到时的逃生门：仍能编辑原始 CSV，否则字典一挂运营连改都改不了。
 *
 * <b>为什么要有 `rawDraft` 这一层，而不是直接 get/set 规范化值</b>：
 * `v-model` 每敲一个字符都会走一遍 set→get。若 get 直接返回 `toCsv(parseCodes(输入))`，
 * 那么敲到 `440300,` 时 parseCodes 会把尾随逗号丢掉、再写回 `440300`——
 * **逗号刚打出来就被吞掉，第二个地域码永远输不进去**，逃生门实际只能填一个码。
 * 所以打字期间原样保留用户的字符串，失焦时再回到规范形（`@blur` 清掉 draft）。
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
  <!--
    `@input.stop` 与 `@click.stop` 缺一不可，理由同一条：EditorView 的 `.form` 上挂的是
    `@input="markDirty" @click="onFormClick"`（:623），**两个都会冒泡上去**。
    只截 click 的话，在搜索框里打一个字就算「改过表单」——清掉刚保存的成功卡、重铸幂等 requestId、
    离开时还弹未保存确认，而运营其实什么都没改。
    真正的脏值来源只有 `codes` 的 setter（EditorView 的 districtCodes computed 里显式 markDirty）。
  -->
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
      它们会<b>原样保留</b>，不会被自动删除。
    </p>

    <div v-if="codes.length" class="chips" data-testid="district-chips">
      <span v-for="c in visibleChips" :key="c" class="chip"
            :class="{ unknown: districts && !isKnown(index, c) }"
            :title="districts ? pathOf(index, c) : c">
        <Badge v-if="districts && !isKnown(index, c)" kind="warn" shape="triangle">?</Badge>
        {{ districts ? labelOf(index, c) : c }}
        <button type="button" class="x" :aria-label="'移除 ' + (districts ? pathOf(index, c) : c)"
                :data-testid="'district-chip-x-' + c" @click="drop(c)">
          <Icon name="x" :size="10" />
        </button>
      </span>
      <button v-if="codes.length > CHIP_PREVIEW" type="button" class="link"
              data-testid="district-chips-more" @click="showAllChips = !showAllChips">
        {{ showAllChips ? '收起' : '+' + (codes.length - CHIP_PREVIEW) }}
      </button>
    </div>
    <p v-else class="hint" data-testid="district-empty-hint">未选择任何地域（等同不投放，保存前请至少选一个或切回「全国」）</p>

    <DistrictCascader v-if="open" :districts="districts" :selected="codes" :loading="loading"
                      @update:selected="codes = $event" />

    <label v-if="failed || (open && !districts)" class="raw">
      <span>地域代码（逗号分隔）</span>
      <input v-model="rawCsv" data-testid="district-raw" placeholder="如 440300,110000"
             @blur="rawDraft = null" />
    </label>
  </div>
</template>

<style scoped>
.dp { display: flex; flex-direction: column; gap: var(--sp-1); min-width: 0; }
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

/* 页面自有的 .chip 在 EditorView 的 scoped 样式里（编译成 .chip[data-v-x]），子组件拿不到那个作用域 id，
   所以这里自带一份而不是「复用」。视觉参数与 EditorView.vue:772 保持一致。 */
.chips { display: flex; flex-wrap: wrap; gap: var(--sp-1); max-height: 30dvh; overflow-y: auto; }
.chip {
  display: inline-flex; align-items: center; gap: 4px;
  border: 1px solid var(--border-ctl); border-radius: var(--radius-pill);
  background: var(--bg-elev); color: var(--text);
  padding: 2px var(--sp-2); font-size: 12.5px; max-width: 100%;
}
.chip.unknown { border-color: var(--warn); }
.x { border: 0; background: transparent; color: var(--text-faint); cursor: pointer; padding: 0 2px; line-height: 1; }
.raw { display: flex; flex-direction: column; gap: 2px; }
.raw input {
  border: 1px solid var(--border-ctl); border-radius: var(--radius-sm);
  background: var(--bg-elev); color: var(--text); padding: var(--sp-1) var(--sp-2); min-height: 38px;
}

@media (pointer: coarse) {
  .toggle, .raw input { min-height: var(--touch-min); }
  /* chip 的删除 × 只有 10px 图标，命中区必须自己撑到 44——tokens.css 那条全局兜底压不过 scoped 样式 */
  .x, .link { min-height: var(--touch-min); min-width: var(--touch-min); }
  .chip { min-height: var(--touch-min); }
}
</style>
