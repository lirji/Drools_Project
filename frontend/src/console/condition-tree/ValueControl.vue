<script setup lang="ts">
// 值控件：按 operand 渲染 SCALAR 单框 / RANGE 双框 / LIST 逗号串。
// v-model 双向绑定根治旧代码"渲染期改写 node.value + oninput 闭包"的反模式；类型归一在 op 变更事件做（父组件），不在渲染期。
import { computed } from 'vue'

const props = defineProps<{ operand: 'SCALAR' | 'RANGE' | 'LIST'; modelValue: string | string[] }>()
const emit = defineEmits<{ 'update:modelValue': [v: string | string[]] }>()

const rangeLo = computed({
  get: () => (Array.isArray(props.modelValue) ? String(props.modelValue[0] ?? '') : ''),
  set: (v: string) => {
    const arr = Array.isArray(props.modelValue) ? [...props.modelValue] : ['', '']
    arr[0] = v
    emit('update:modelValue', arr)
  },
})
const rangeHi = computed({
  get: () => (Array.isArray(props.modelValue) ? String(props.modelValue[1] ?? '') : ''),
  set: (v: string) => {
    const arr = Array.isArray(props.modelValue) ? [...props.modelValue] : ['', '']
    arr[1] = v
    emit('update:modelValue', arr)
  },
})
const listStr = computed({
  get: () => (Array.isArray(props.modelValue) ? props.modelValue.join(',') : ''),
  set: (v: string) => emit('update:modelValue', v.split(',').map((s) => s.trim()).filter((s) => s !== '')),
})
const scalar = computed({
  get: () => (typeof props.modelValue === 'string' ? props.modelValue : ''),
  set: (v: string) => emit('update:modelValue', v),
})
</script>

<template>
  <div class="value-ctl">
    <template v-if="operand === 'RANGE'">
      <input class="inp" v-model="rangeLo" placeholder="下界" data-testid="range-lo" />
      <span class="tilde">~</span>
      <input class="inp" v-model="rangeHi" placeholder="上界" data-testid="range-hi" />
    </template>
    <input v-else-if="operand === 'LIST'" class="inp wide" v-model="listStr" placeholder="逗号分隔多个值" data-testid="list-val" />
    <input v-else class="inp wide" v-model="scalar" placeholder="值" data-testid="scalar-val" />
  </div>
</template>

<style scoped>
.value-ctl { display: flex; align-items: center; gap: var(--sp-1); flex-wrap: wrap; }
.inp {
  padding: var(--sp-1) var(--sp-2); border: 1px solid var(--border-ctl);
  border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text); min-width: 80px;
}
.inp.wide { flex: 1; min-width: 140px; }
.tilde { color: var(--text-faint); }
</style>
