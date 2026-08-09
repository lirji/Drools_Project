// 全局 toast —— 替换旧前端 4 处裸 alert()（UI/UX 勘察点名）。带 aria-live 播报（可达性）。
import { ref } from 'vue'

/**
 * Toast 上的动作位（PR-5 新增）。
 *
 * <p>批量操作的**回执**与**撤销**必须挂在 toast 上：运营点完「批量下线 23 个」，
 * 如果只看到一句「已完成」，就不知道到底成了几个——而大促前这是最高危的操作，
 * 静默失败等于让运营以为活动停了、实际还在发钱。
 */
export interface ToastAction {
  label: string
  onClick: () => void
  /** 点完不关这条 toast（默认点完就关） */
  keepOpen?: boolean
  testid?: string
}

export interface Toast {
  id: number
  kind: 'info' | 'ok' | 'err' | 'warn'
  msg: string
  actions?: ToastAction[]
  /** 剩余秒数。只有「过期即失效」的窗口（如撤销）才显示，null = 不显示倒计时 */
  remain: number | null
}

export interface ToastOptions {
  kind?: Toast['kind']
  /** 毫秒；<= 0 表示常驻，只能由用户点掉或代码 dismiss */
  ttl?: number
  actions?: ToastAction[]
  /** 显示秒级倒计时（需 ttl > 0） */
  countdown?: boolean
  /** ttl 到点自动关闭时回调。撤销窗口靠它落地「窗口已过、不可再撤销」 */
  onExpire?: () => void
}

const toasts = ref<Toast[]>([])
/** 定时器与 toast 分开存：Toast 要能被结构化克隆/比较，塞进 timer 句柄会污染它 */
const timers = new Map<number, { close?: ReturnType<typeof setTimeout>; tick?: ReturnType<typeof setInterval> }>()
let seq = 0

function clearTimers(id: number): void {
  const t = timers.get(id)
  if (!t) return
  if (t.close) clearTimeout(t.close)
  if (t.tick) clearInterval(t.tick)
  timers.delete(id)
}

function dismiss(id: number): void {
  clearTimers(id)
  toasts.value = toasts.value.filter((t) => t.id !== id)
}

/**
 * 通用入口。返回 toast id，调用方可主动 dismiss（如撤销执行后立刻收掉回执条）。
 */
function show(msg: string, opts: ToastOptions = {}): number {
  const { kind = 'info', ttl = 4000, actions, countdown = false, onExpire } = opts
  const id = ++seq
  toasts.value = [...toasts.value, {
    id, kind, msg, actions,
    remain: countdown && ttl > 0 ? Math.ceil(ttl / 1000) : null,
  }]
  if (ttl > 0) {
    const entry: { close?: ReturnType<typeof setTimeout>; tick?: ReturnType<typeof setInterval> } = {}
    entry.close = setTimeout(() => {
      dismiss(id)
      onExpire?.()
    }, ttl)
    if (countdown) {
      entry.tick = setInterval(() => {
        toasts.value = toasts.value.map((t) =>
          t.id === id && t.remain !== null ? { ...t, remain: Math.max(0, t.remain - 1) } : t)
      }, 1000)
    }
    timers.set(id, entry)
  }
  return id
}

export function useToast() {
  function push(kind: Toast['kind'], msg: string, ttl = 4000): void {
    show(msg, { kind, ttl })
  }
  return {
    toasts,
    show,
    info: (m: string) => push('info', m),
    ok: (m: string) => push('ok', m),
    err: (m: string) => push('err', m, 6000),
    warn: (m: string) => push('warn', m),
    dismiss,
  }
}
