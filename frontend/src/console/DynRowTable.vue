<script setup lang="ts" generic="T">
// 泛型动态行表 —— 阶梯档/赠品/SPU/商品池四处复用。scoped slot 定义单元格；增删只动数组，Vue keyed diff 免整表重建。
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
    <div class="scroll" :style="{ minWidth: (minWidth || 420) + 'px' }">
      <div class="row head">
        <span class="idx">#</span>
        <span v-for="h in headers" :key="h">{{ h }}</span>
        <span class="act" />
      </div>
      <div v-if="!rows.length" class="empty">暂无，点下方添加</div>
      <div v-for="(row, i) in rows" :key="i" class="row" data-testid="dyn-row">
        <span class="idx mono">{{ i + 1 }}</span>
        <slot :row="row" :index="i" />
        <button class="del" :aria-label="'删除第' + (i + 1) + '行'" @click="remove(i)">✕</button>
      </div>
    </div>
    <button class="add" data-testid="dyn-add" @click="add">+ 添加{{ label }}</button>
  </div>
</template>

<style scoped>
.row-group { margin: var(--sp-2) 0; }
.scroll { overflow-x: auto; }
.row { display: flex; align-items: center; gap: var(--sp-2); padding: var(--sp-1) 0; }
.row.head { font-size: 12px; color: var(--text-soft); font-weight: 600; }
.idx { width: 24px; text-align: center; }
.act { width: 32px; }
.mono { font-family: var(--mono); }
.empty { color: var(--text-faint); font-size: 12px; padding: var(--sp-2) 0; }
.del { border: 1px solid var(--border); background: var(--bg-soft); color: var(--red); border-radius: var(--radius-sm); padding: var(--sp-1) var(--sp-2); cursor: pointer; }
.add { margin-top: var(--sp-2); border: 1px dashed var(--border-strong); background: transparent; color: var(--accent); border-radius: var(--radius-sm); padding: var(--sp-2) var(--sp-3); cursor: pointer; font-size: 13px; }
:slotted(input), :slotted(select) {
  padding: var(--sp-1) var(--sp-2); border: 1px solid var(--border);
  border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); width: 100%;
}
@media (pointer: coarse) { .del { min-width: var(--touch-min); min-height: var(--touch-min); } }
</style>
