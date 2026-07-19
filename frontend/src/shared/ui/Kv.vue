<script setup lang="ts">
defineProps<{ k: string; mono?: boolean }>()
</script>
<template>
  <div class="kv">
    <span class="kv-k">{{ k }}</span>
    <span class="kv-v" :class="{ mono }"><slot /></span>
  </div>
</template>
<style scoped>
.kv { display: flex; justify-content: space-between; gap: var(--sp-4); padding: var(--sp-1) 0; font-size: 13px; }
.kv-k { color: var(--text-soft); }
.kv-v { color: var(--text); text-align: right; }
.mono { font-family: var(--mono); }
</style>
