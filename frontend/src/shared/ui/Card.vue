<script setup lang="ts">
defineProps<{ title?: string }>()
</script>
<template>
  <div class="card">
    <div v-if="title" class="card-title">{{ title }}</div>
    <slot />
  </div>
</template>
<style scoped>
.card {
  background: var(--bg-elev); border: 1px solid var(--border);
  border-radius: var(--radius); padding: var(--sp-4); box-shadow: var(--shadow-sm);
  margin-bottom: var(--sp-3);
}
.card-title { font-weight: 600; margin-bottom: var(--sp-3); font-size: 14px; }
</style>
