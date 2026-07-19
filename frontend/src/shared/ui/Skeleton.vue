<script setup lang="ts">
defineProps<{ rows?: number }>()
</script>
<template>
  <div class="skel" aria-busy="true" data-testid="skeleton">
    <div v-for="i in (rows || 3)" :key="i" class="skel-row" />
  </div>
</template>
<style scoped>
.skel { display: flex; flex-direction: column; gap: var(--sp-2); padding: var(--sp-3) 0; }
.skel-row {
  height: 16px; border-radius: var(--radius-sm);
  background: linear-gradient(90deg, var(--bg-soft) 25%, var(--border) 37%, var(--bg-soft) 63%);
  background-size: 400% 100%; animation: shimmer 1.4s ease infinite;
}
@keyframes shimmer { 0% { background-position: 100% 0; } 100% { background-position: -100% 0; } }
@media (prefers-reduced-motion: reduce) { .skel-row { animation: none; } }
</style>
