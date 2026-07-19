<script setup lang="ts">
defineProps<{ kind?: 'info' | 'ok' | 'err' | 'warn' }>()
</script>
<template>
  <div class="banner" :class="'banner-' + (kind || 'info')"><slot /></div>
</template>
<style scoped>
.banner {
  padding: var(--sp-2) var(--sp-3); border-radius: var(--radius-sm);
  font-size: 13px; margin: var(--sp-2) 0; border: 1px solid var(--border);
}
.banner-info { background: var(--blue-soft); color: var(--blue); border-color: transparent; }
.banner-ok { background: var(--ok-soft); color: var(--green); border-color: transparent; }
.banner-warn { background: var(--gold-soft); color: var(--gold); border-color: transparent; }
.banner-err { background: var(--err-soft); color: var(--err); border-color: transparent; }
</style>
