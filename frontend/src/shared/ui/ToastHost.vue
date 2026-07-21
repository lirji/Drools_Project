<script setup lang="ts">
import { useToast } from '../useToast'
const { toasts, dismiss } = useToast()
</script>

<template>
  <div class="toast-host" aria-live="polite" data-testid="toast-host">
    <transition-group name="toast">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="'toast-' + t.kind" @click="dismiss(t.id)">
        {{ t.msg }}
      </div>
    </transition-group>
  </div>
</template>

<style scoped>
.toast-host {
  position: fixed; top: var(--sp-4); right: var(--sp-4); z-index: 1000;
  display: flex; flex-direction: column; gap: var(--sp-2); max-width: 360px;
}
.toast {
  padding: var(--sp-3) var(--sp-4); border-radius: var(--radius-sm);
  box-shadow: var(--shadow-md); font-size: 13px; cursor: pointer;
  background: var(--bg-elev); border: 1px solid var(--border); color: var(--text);
}
/* 入场/退场动画（被全局 reduced-motion 兜底禁用） */
.toast-enter-active, .toast-leave-active { transition: opacity .18s ease, transform .18s ease; }
.toast-enter-from { opacity: 0; transform: translateX(16px); }
.toast-leave-to { opacity: 0; transform: translateX(16px); }
.toast-leave-active { position: absolute; right: 0; }
.toast-ok { border-left: 3px solid var(--ok); }
.toast-err { border-left: 3px solid var(--err); }
.toast-warn { border-left: 3px solid var(--warn); }
.toast-info { border-left: 3px solid var(--blue); }
</style>
