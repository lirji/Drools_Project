// 全局 toast —— 替换旧前端 4 处裸 alert()（UI/UX 勘察点名）。带 aria-live 播报（可达性）。
import { ref } from 'vue'

export interface Toast {
  id: number
  kind: 'info' | 'ok' | 'err' | 'warn'
  msg: string
}

const toasts = ref<Toast[]>([])
let seq = 0

export function useToast() {
  function push(kind: Toast['kind'], msg: string, ttl = 4000): void {
    const id = ++seq
    toasts.value = [...toasts.value, { id, kind, msg }]
    if (ttl > 0) setTimeout(() => dismiss(id), ttl)
  }
  function dismiss(id: number): void {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }
  return {
    toasts,
    info: (m: string) => push('info', m),
    ok: (m: string) => push('ok', m),
    err: (m: string) => push('err', m, 6000),
    warn: (m: string) => push('warn', m),
    dismiss,
  }
}
