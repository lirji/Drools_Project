<script setup lang="ts">
import { useToast, type Toast, type ToastAction } from '../useToast'
const { toasts, dismiss } = useToast()

function run(t: Toast, a: ToastAction): void {
  a.onClick()
  if (!a.keepOpen) dismiss(t.id)
}
</script>

<template>
  <div class="toast-host" aria-live="polite" data-testid="toast-host">
    <transition-group name="toast">
      <div
        v-for="t in toasts"
        :key="t.id"
        class="toast"
        :class="['toast-' + t.kind, { rich: !!t.actions?.length }]"
        :data-testid="'toast-' + t.kind"
        @click="t.actions?.length ? undefined : dismiss(t.id)"
      >
        <p class="msg">{{ t.msg }}</p>
        <div v-if="t.actions?.length" class="acts">
          <button
            v-for="a in t.actions"
            :key="a.label"
            type="button"
            :data-testid="a.testid"
            @click.stop="run(t, a)"
          >
            {{ a.label }}
            <!-- 倒计时对屏幕阅读器隐藏：整个 host 是 aria-live 区，秒级变化会让读屏每秒重播一遍。
                 首次播报已含「撤销」字样，数字只是给视觉用户的紧迫感提示。 -->
            <span v-if="t.remain !== null" class="remain" aria-hidden="true">{{ t.remain }}s</span>
          </button>
          <button type="button" class="close" aria-label="关闭" @click.stop="dismiss(t.id)">×</button>
        </div>
      </div>
    </transition-group>
  </div>
</template>

<style scoped>
.toast-host {
  position: fixed; top: var(--sp-4); right: var(--sp-4); z-index: var(--z-toast);
  display: flex; flex-direction: column; gap: var(--sp-2); max-width: 380px;
}
.toast {
  padding: var(--sp-3) var(--sp-4); border-radius: var(--radius-sm);
  box-shadow: var(--shadow-md); font-size: 13px; cursor: pointer;
  background: var(--bg-elev); border: 1px solid var(--border); color: var(--text);
}
/* 带动作位的 toast 不能整条点掉——瞄准「撤销」时手抖点到边上就把撤销窗口关了 */
.toast.rich { cursor: default; }
.msg { margin: 0; }
.acts { display: flex; align-items: center; gap: var(--sp-2); margin-top: var(--sp-2); }
.acts button {
  display: inline-flex; align-items: center; gap: 4px;
  min-height: 28px; padding: 2px var(--sp-3);
  border: 1px solid var(--border); border-radius: var(--radius-sm);
  background: var(--bg-soft); color: var(--text); cursor: pointer;
  font: inherit; font-size: var(--fs-xs);
}
.acts button:hover { background: var(--bg-hover); }
.acts .close { margin-left: auto; border-color: transparent; background: transparent; color: var(--text-faint); font-size: 15px; line-height: 1; }
.remain { font-family: var(--mono); font-size: 10px; color: var(--text-faint); font-variant-numeric: tabular-nums; }
/* 入场/退场动画（被全局 reduced-motion 兜底禁用） */
.toast-enter-active, .toast-leave-active { transition: opacity .18s ease, transform .18s ease; }
.toast-enter-from { opacity: 0; transform: translateX(16px); }
.toast-leave-to { opacity: 0; transform: translateX(16px); }
.toast-leave-active { position: absolute; right: 0; }
.toast-ok { border-left: 3px solid var(--ok); }
.toast-err { border-left: 3px solid var(--err); }
.toast-warn { border-left: 3px solid var(--warn); }
.toast-info { border-left: 3px solid var(--blue); }
@media (pointer: coarse) { .acts button { min-height: var(--touch-min); } }
</style>
