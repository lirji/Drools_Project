// 计数式 body 滚动锁（UX 重设计 Phase D，评审 M4）。
// 抽屉与 ConfirmDialog 可能同时想锁 body 滚动；直写 body.style.overflow 会互相覆盖/误清。
// 用引用计数：第一个 lock() 记住原值并置 hidden，最后一个 unlock() 恢复原值。
let count = 0
let prevOverflow = ''

export function lockScroll(): void {
  if (count === 0) {
    prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  }
  count++
}

export function unlockScroll(): void {
  if (count === 0) return
  count--
  if (count === 0) {
    document.body.style.overflow = prevOverflow
  }
}
