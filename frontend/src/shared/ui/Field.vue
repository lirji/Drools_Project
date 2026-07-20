<script setup lang="ts">
/**
 * 表单字段原语（重设计）：label + 控件槽 + 提示 + 字段级错误，收敛散落的 .fg 三处。控件经默认 slot 传入。
 */
defineProps<{ label?: string; hint?: string; error?: string }>()
</script>

<template>
  <label class="field" :class="{ 'has-error': !!error }">
    <span v-if="label" class="lbl">{{ label }}</span>
    <slot />
    <span v-if="error" class="err">{{ error }}</span>
    <span v-else-if="hint" class="hint">{{ hint }}</span>
  </label>
</template>

<style scoped>
.field { display: flex; flex-direction: column; gap: var(--sp-1); }
.lbl { font-size: var(--fs-xs); color: var(--text-soft); font-weight: var(--fw-medium); }
.hint { font-size: var(--fs-xs); color: var(--text-faint); }
.err { font-size: var(--fs-xs); color: var(--err); }
.field.has-error :slotted(input),
.field.has-error :slotted(select),
.field.has-error :slotted(textarea) { border-color: var(--err); }
</style>
