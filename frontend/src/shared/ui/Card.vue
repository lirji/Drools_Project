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
/* 承载层 ::before 用于渐变描边／发光边——单层 div + border 画不出这类效果。
   默认不显形（opacity 0），由使用处按需 opt-in，避免全站卡片一律发光。 */
.card {
  position: relative;
  background: var(--bg-elev); border: 1px solid var(--border);
  border-radius: var(--radius); padding: var(--sp-4); box-shadow: var(--shadow-sm);
  margin-bottom: var(--sp-3);
  transition: border-color var(--dur-mid) var(--ease-out), box-shadow var(--dur-mid) var(--ease-out);
}
.card::before {
  content: ''; position: absolute; inset: -1px; border-radius: inherit;
  padding: 1px; opacity: 0; pointer-events: none;
  background: linear-gradient(135deg, var(--accent), transparent 42%, transparent 58%, var(--accent-2));
  -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  -webkit-mask-composite: xor;
  mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  mask-composite: exclude;
  transition: opacity var(--dur-mid) var(--ease-out);
}
.card.is-accent::before { opacity: 1; }
.card-title { font-weight: 600; margin-bottom: var(--sp-3); font-size: 14px; }
</style>
