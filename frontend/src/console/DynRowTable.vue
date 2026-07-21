<script setup lang="ts" generic="T">
// 泛型动态行表 —— 阶梯档/赠品/SPU/商品池四处复用。scoped slot 定义单元格；增删只动数组，Vue keyed diff 免整表重建。
import Icon from '@/shared/ui/Icon.vue'

const props = defineProps<{
  rows: T[]
  headers: string[]
  makeRow: () => T
  label: string
  minWidth?: number
}>()

function add(): void {
  props.rows.push(props.makeRow())
}
function remove(i: number): void {
  props.rows.splice(i, 1)
}
</script>

<template>
  <div class="row-group">
    <div class="scroll">
      <div class="table" :style="{ minWidth: (minWidth || 420) + 'px' }">
      <div class="row head">
        <span class="idx">#</span>
        <span v-for="h in headers" :key="h">{{ h }}</span>
        <span class="act" />
      </div>
      <div v-if="!rows.length" class="empty">暂无，点下方添加</div>
      <div v-for="(row, i) in rows" :key="i" class="row" data-testid="dyn-row">
        <span class="idx">{{ i + 1 }}</span>
        <slot :row="row" :index="i" />
        <button type="button" class="del" :aria-label="'删除第' + (i + 1) + '行'" @click="remove(i)"><Icon name="trash" :size="14" /></button>
      </div>
      </div>
    </div>
    <button type="button" class="add" data-testid="dyn-add" @click="add"><Icon name="plus" :size="14" /> 添加{{ label }}</button>
  </div>
</template>

<style scoped>
.row-group { min-width: 0; margin: var(--sp-2) 0; }
.scroll { max-width: 100%; overflow-x: auto; border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-soft); }
.table { padding: var(--sp-2); }
.row { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-1) 0; }
.row + .row:not(.head) { border-top: 1px solid var(--border); }
.row.head { color: var(--text-faint); font-size: 10px; font-weight: var(--fw-bold); letter-spacing: .03em; text-transform: uppercase; }
.idx { width: 24px; font-variant-numeric: tabular-nums; text-align: center; }
.act { width: 32px; }
.empty { color: var(--text-faint); font-size: 11px; padding: var(--sp-3); text-align: center; }
.del { display: inline-flex; align-items: center; justify-content: center; border: 1px solid var(--border); background: var(--bg-elev); color: var(--red); border-radius: var(--radius-sm); padding: var(--sp-1) var(--sp-2); cursor: pointer; }
.del:hover { border-color: var(--red); background: var(--red-soft); }
.add { display: inline-flex; align-items: center; gap: var(--sp-1); margin-top: var(--sp-2); border: 1px dashed var(--accent-line); background: var(--accent-soft); color: var(--accent); border-radius: var(--radius-sm); padding: var(--sp-2) var(--sp-3); cursor: pointer; font-size: 12px; font-weight: var(--fw-medium); }
.add:hover { border-style: solid; }
:slotted(input), :slotted(select) {
  min-height: 34px; padding: var(--sp-1) var(--sp-2); border: 1px solid var(--border);
  border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); width: 100%;
}
@media (pointer: coarse) { .del { min-width: var(--touch-min); min-height: var(--touch-min); } }
</style>
