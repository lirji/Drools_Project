<script setup lang="ts">
import { RouterView } from 'vue-router'
import DemoNav from './DemoNav.vue'
</script>

<template>
  <div class="demos-layout">
    <aside class="demos-side">
      <DemoNav />
    </aside>
    <section class="demos-panel">
      <RouterView />
    </section>
  </div>
</template>

<style scoped>
.demos-layout { display: flex; max-width: 1360px; margin: 0 auto; gap: var(--sp-4); padding: var(--sp-4); }
.demos-side { width: 268px; flex-shrink: 0; }
.demos-panel { flex: 1; min-width: 0; }
@media (max-width: 900px) {
  .demos-layout { flex-direction: column; }
  .demos-side { width: 100%; }
}
</style>
