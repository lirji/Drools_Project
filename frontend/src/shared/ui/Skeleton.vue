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
/* 扫光换成「accent 微光扫过 + 底面」双层：换代前是中性灰阶推移，在深空底上几乎看不出在动。
   关键帧走 effects.css 的全局 sweep，避免各页面重复定义。 */
.skel-row {
  height: 16px; border-radius: var(--radius-sm);
  background:
    linear-gradient(90deg, transparent 0%, color-mix(in srgb, var(--accent) 16%, transparent) 45%, transparent 90%),
    var(--bg-soft);
  background-size: 220% 100%, 100% 100%;
  background-repeat: no-repeat;
  animation: sweep 1.6s var(--ease-out) infinite;
}

@media (prefers-reduced-motion: reduce) { .skel-row { animation: none; } }
</style>
