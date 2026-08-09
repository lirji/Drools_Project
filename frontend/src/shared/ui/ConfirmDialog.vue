<script setup lang="ts">
/**
 * 确认弹窗宿主（UX 重设计 Phase D）：全局挂载一份（App.vue，仿 ToastHost），消费 useConfirm 的单例状态。
 * role=dialog + aria-modal；Esc/scrim=取消；打开时焦点移入确认键 + 计数式锁 body 滚动；z=--z-modal(950)，在抽屉之上 toast 之下。
 */
import { nextTick, onUnmounted, ref, watch } from 'vue'
import { useConfirm } from '@/shared/useConfirm'
import { lockScroll, unlockScroll } from '@/shared/useScrollLock'
import Icon from './Icon.vue'

const { active, settle } = useConfirm()
const okBtn = ref<HTMLButtonElement | null>(null)
let locked = false

function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape' && active.value) {
    e.stopPropagation()
    settle(false)
  }
}

watch(active, async (cur) => {
  if (cur) {
    if (!locked) { lockScroll(); locked = true }
    window.addEventListener('keydown', onKey, true)
    await nextTick()
    okBtn.value?.focus()
  } else {
    window.removeEventListener('keydown', onKey, true)
    if (locked) { unlockScroll(); locked = false }
  }
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKey, true)
  if (locked) { unlockScroll(); locked = false }
})
</script>

<template>
  <Transition name="dlg">
    <div v-if="active" class="scrim" @click.self="settle(false)">
      <div
        class="dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="active.title"
        data-testid="confirm-dialog"
      >
        <div class="d-head">
          <span class="d-ic" :class="{ danger: active.danger }">
            <Icon :name="active.danger ? 'alert-triangle' : 'info'" :size="20" />
          </span>
          <h2 class="d-title">{{ active.title }}</h2>
        </div>
        <p v-if="active.body" class="d-body">{{ active.body }}</p>
        <div class="d-acts">
          <button class="btn cancel" data-testid="confirm-cancel" @click="settle(false)">
            {{ active.cancelText || '取消' }}
          </button>
          <button
            ref="okBtn"
            class="btn ok"
            :class="{ danger: active.danger }"
            data-testid="confirm-ok"
            @click="settle(true)"
          >{{ active.confirmText || '确定' }}</button>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.scrim {
  position: fixed; inset: 0; z-index: var(--z-modal);
  display: flex; align-items: center; justify-content: center; padding: var(--sp-4);
  /* PR-0 把 AppLayout 的硬编码遮罩提成了 --scrim，但漏了这一处（全仓最后一个硬编码颜色）。
     深色下 rgba(15,17,23,.45) 压不住内容。 */
  background: var(--scrim);
}
.dialog {
  width: 100%; max-width: 400px; background: var(--bg-elev);
  border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg); padding: var(--sp-5);
}
.d-head { display: flex; align-items: center; gap: var(--sp-3); margin-bottom: var(--sp-3); }
.d-ic {
  display: inline-flex; align-items: center; justify-content: center; flex: 0 0 auto;
  width: 36px; height: 36px; border-radius: var(--radius); background: var(--accent-soft); color: var(--accent);
}
.d-ic.danger { background: var(--err-soft); color: var(--err); }
.d-title { margin: 0; font-size: var(--fs-lg); font-weight: var(--fw-semibold); }
.d-body { margin: 0 0 var(--sp-5); font-size: var(--fs-sm); color: var(--text-soft); line-height: var(--lh-normal); }
.d-acts { display: flex; justify-content: flex-end; gap: var(--sp-2); }
.btn {
  min-height: 38px; padding: var(--sp-2) var(--sp-4); border-radius: var(--radius-sm);
  border: 1px solid var(--border); background: var(--bg-elev); color: var(--text);
  font-size: var(--fs-sm); font-weight: var(--fw-medium); font-family: inherit; cursor: pointer;
  transition: background .12s ease, border-color .12s ease;
}
.btn:hover { background: var(--bg-hover); }
/* 压在 accent 上的字走 --text-invert，不写死 #fff——深色态 accent 是浅粉 #f45ca0，
   白字对比度不够（评审 X7 点名的正是这条，token 在 PR-1 就加了但这里没接上）。 */
.btn.ok { background: var(--accent); border-color: var(--accent); color: var(--text-invert); }
.btn.ok:hover { background: var(--accent-hover); border-color: var(--accent-hover); }
.btn.ok.danger { background: var(--err); border-color: var(--err); }
.btn.ok.danger:hover { filter: brightness(1.06); }
@media (pointer: coarse) { .btn { min-height: var(--touch-min); } }

.dlg-enter-active, .dlg-leave-active { transition: opacity .16s ease; }
.dlg-enter-active .dialog, .dlg-leave-active .dialog { transition: transform .16s ease, opacity .16s ease; }
.dlg-enter-from, .dlg-leave-to { opacity: 0; }
.dlg-enter-from .dialog, .dlg-leave-to .dialog { transform: translateY(8px) scale(.98); opacity: 0; }
</style>
