// 表格密度两档（PR-5）。写法与主题一致：`<html data-density>` + localStorage。
//
// 刻意**不用** `body:has(#d-compact:checked)`（评审 X2 点名的致命项）：
//   ① `position:fixed` 的侧板 / 模态 / Toast 在 body 子树之外，拿不到定义在 body 上的变量；
//   ② radio 状态无法持久化，刷新即回落；
//   ③ `:has()` 在 Firefox <121 静默失效——失效方式是"点了没反应"，最难排查。
import { ref } from 'vue'

export const DENSITY_KEY = 'drools-density'
export type Density = 'comfy' | 'compact'

/** 舒适是默认档：不认识的存值一律回落到它，而不是抛错或留空属性 */
export function readDensity(): Density {
  try {
    return localStorage.getItem(DENSITY_KEY) === 'compact' ? 'compact' : 'comfy'
  } catch {
    return 'comfy'
  }
}

/** 舒适档不写属性——让 `:root` 的默认值直接生效，DOM 上少一个恒真的标记 */
export function applyDensity(d: Density): void {
  if (d === 'compact') document.documentElement.setAttribute('data-density', 'compact')
  else document.documentElement.removeAttribute('data-density')
}

// 模块单例（仿 useToast / useConfirm）：多处挂切换控件时状态必须共享，
// 否则表格用的是 A 实例、工具条上的按钮改的是 B 实例。
const density = ref<Density>(readDensity())

export function useDensity() {
  function setDensity(d: Density): void {
    density.value = d
    applyDensity(d)
    try {
      localStorage.setItem(DENSITY_KEY, d)
    } catch {
      /* 隐私模式下写不进去也不该影响本次会话内的切换 */
    }
  }
  return { density, setDensity }
}
