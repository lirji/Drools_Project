<script setup lang="ts">
// 递归分组（AND/OR）—— Vue SFC 自引用递归。:key=node.id（稳定临时 id，禁 index），根治旧 reTree() 整树重建丢焦点。
import type { GroupNode, DictField, DictOperator } from '@/shared/types'
import { isGroup } from '@/shared/types'
import { emptyLeaf, emptyGroup } from '../logic'
import ConditionLeaf from './ConditionLeaf.vue'

const props = defineProps<{
  node: GroupNode
  fields: DictField[]
  operators: DictOperator[]
  depth: number
  root?: boolean
}>()
const emit = defineEmits<{ remove: [] }>()

const MAX_DEPTH = 4

function addCond(): void {
  props.node.children.push(emptyLeaf(props.fields, props.operators))
}
function addGroup(): void {
  if (props.depth < MAX_DEPTH) props.node.children.push(emptyGroup())
}
function removeChild(i: number): void {
  props.node.children.splice(i, 1)
}
</script>

<template>
  <div class="group" data-testid="cond-group">
    <div class="group-head">
      <div class="logic-toggle">
        <button
          v-for="l in (['AND', 'OR'] as const)"
          :key="l"
          class="chip"
          :class="{ 'chip-active': node.logic === l }"
          :data-testid="'logic-' + l"
          @click="node.logic = l"
        >
          {{ l === 'AND' ? '且 AND' : '或 OR' }}
        </button>
      </div>
      <span class="spacer" />
      <button class="mini" data-testid="add-cond" @click="addCond">+ 条件</button>
      <button class="mini" :disabled="depth >= MAX_DEPTH" data-testid="add-group" @click="addGroup">+ 分组</button>
      <button v-if="!root" class="mini danger" data-testid="del-group" @click="emit('remove')">🗑 删组</button>
    </div>

    <div class="children">
      <div v-if="!node.children.length" class="empty">空分组：添加条件或删除（提交时自动剪除）</div>
      <template v-for="(child, i) in node.children" :key="child.id">
        <ConditionGroup
          v-if="isGroup(child)"
          :node="child"
          :fields="fields"
          :operators="operators"
          :depth="depth + 1"
          @remove="removeChild(i)"
        />
        <ConditionLeaf
          v-else
          :node="child"
          :fields="fields"
          :operators="operators"
          @remove="removeChild(i)"
        />
      </template>
    </div>
  </div>
</template>

<style scoped>
.group { border: 1px solid var(--border); border-radius: var(--radius-sm); padding: var(--sp-2); margin: var(--sp-1) 0; background: var(--bg-soft); }
.group-head { display: flex; align-items: center; gap: var(--sp-2); }
.logic-toggle { display: flex; gap: var(--sp-1); }
.spacer { flex: 1; }
.chip { padding: var(--sp-1) var(--sp-2); border: 1px solid var(--border); border-radius: 999px; background: var(--bg-elev); color: var(--text); font-size: 12px; cursor: pointer; }
.chip-active { background: var(--accent); color: #fff; border-color: var(--accent); }
.mini { padding: var(--sp-1) var(--sp-2); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); font-size: 12px; cursor: pointer; }
.mini:disabled { opacity: .5; cursor: not-allowed; }
.mini.danger { color: var(--red); }
.children { border-left: 2px solid var(--border); margin-left: var(--sp-2); padding-left: var(--sp-3); margin-top: var(--sp-2); }
.empty { color: var(--text-faint); font-size: 12px; padding: var(--sp-1) 0; }
@media (pointer: coarse) { .mini, .chip { min-height: var(--touch-min); } }
</style>
