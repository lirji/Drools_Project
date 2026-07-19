<script setup lang="ts">
// 条件叶子：字段选择 → 运算符联动 → 值控件。字段/运算符全从 field-dict 白名单来（永不裸 DRL）。
import { computed } from 'vue'
import type { LeafNode, DictField, DictOperator } from '@/shared/types'
import { operandOf, fieldByKey, emptyValue } from '../logic'
import ValueControl from './ValueControl.vue'

const props = defineProps<{ node: LeafNode; fields: DictField[]; operators: DictOperator[] }>()
const emit = defineEmits<{ remove: [] }>()

const curField = computed(() => fieldByKey(props.node.field, props.fields) || props.fields[0])
const opsForField = computed(() => curField.value?.operators || [])
const operand = computed(() => operandOf(props.node.op, props.operators))

function opLabel(code: string): string {
  return props.operators.find((o) => o.code === code)?.label || code
}

// 换字段：重置运算符到该字段首个 + 值置空（显式事件逻辑，非渲染期副作用）
function onFieldChange(e: Event): void {
  const key = (e.target as HTMLSelectElement).value
  props.node.field = key
  const nf = fieldByKey(key, props.fields)
  props.node.op = (nf?.operators || [])[0] || ''
  props.node.value = emptyValue(operandOf(props.node.op, props.operators))
}
// 换运算符：值按新 operand 置空
function onOpChange(e: Event): void {
  const op = (e.target as HTMLSelectElement).value
  props.node.op = op
  props.node.value = emptyValue(operandOf(op, props.operators))
}
</script>

<template>
  <div class="leaf" data-testid="cond-leaf">
    <select class="sel" :value="node.field" aria-label="字段" data-testid="leaf-field" @change="onFieldChange">
      <option v-for="f in fields" :key="f.key" :value="f.key">{{ f.label }}</option>
    </select>
    <select class="sel" :value="node.op" aria-label="运算符" data-testid="leaf-op" @change="onOpChange">
      <option v-for="c in opsForField" :key="c" :value="c">{{ opLabel(c) }}</option>
    </select>
    <ValueControl :operand="operand" v-model="node.value" />
    <button class="del" aria-label="删除条件" data-testid="leaf-del" @click="emit('remove')">✕</button>
  </div>
</template>

<style scoped>
.leaf { display: flex; align-items: center; gap: var(--sp-2); flex-wrap: wrap; padding: var(--sp-1) 0; }
.sel {
  padding: var(--sp-1) var(--sp-2); border: 1px solid var(--border);
  border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text);
}
.del {
  border: 1px solid var(--border); background: var(--bg-soft); color: var(--red);
  border-radius: var(--radius-sm); padding: var(--sp-1) var(--sp-2); cursor: pointer;
}
@media (pointer: coarse) { .del { min-width: var(--touch-min); min-height: var(--touch-min); } }
</style>
