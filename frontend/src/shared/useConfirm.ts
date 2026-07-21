// 全局确认弹窗（UX 重设计 Phase D）：Promise 化 confirm()，替代原生 window.confirm 与「无二次确认」的破坏性操作。
// 模块单例（仿 useToast）：唯一状态 + 全局挂载的 ConfirmDialog 宿主消费。单飞——新请求自动取消旧挂起（评审轻微 7）。
import { ref } from 'vue'

export interface ConfirmOptions {
  title: string
  body?: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
}

interface ActiveConfirm extends ConfirmOptions {
  id: number
  resolve: (ok: boolean) => void
}

const active = ref<ActiveConfirm | null>(null)
let seq = 0

export function useConfirm() {
  function confirm(opts: ConfirmOptions): Promise<boolean> {
    // 单飞：已有挂起的确认先按取消 resolve，避免 Promise 悬挂
    if (active.value) active.value.resolve(false)
    return new Promise<boolean>((resolve) => {
      active.value = { id: ++seq, ...opts, resolve }
    })
  }
  function settle(ok: boolean): void {
    const cur = active.value
    active.value = null
    cur?.resolve(ok)
  }
  return { active, confirm, settle }
}
