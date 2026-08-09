<script setup lang="ts">
/**
 * 右侧详情板（PR-5）。三档响应式：
 *   ≥1280px  push    —— 由父容器让出 `--panel-w` 一列，列表**仍可交互**（非模态）
 *   1024–1279 overlay —— fixed 覆盖 + scrim（模态）
 *   <1024     sheet   —— 全屏抽屉（模态）
 *
 * <p>用 `--z-panel: 880`，**刻意低于** `--z-drawer: 900`：<768 的导航抽屉与侧板同开时，
 * 若同级则只能由 DOM 顺序决胜负（评审 X9）。
 *
 * <p>滚动锁的解锁阈值取 **1280**（模态结束的那条线），不是评审 X10 原文写的 1024。
 * X10 要修的是「1000px 开侧板 → 拖到 1400px 后 body.overflow='hidden' 永久留下」，
 * 而按字面取 1024 会让 1024–1279 这段**带 scrim 的 overlay 不加锁**——
 * 结果是内容在遮罩底下照样滚。模态在哪结束，锁就该在哪解。
 */
import { onBeforeUnmount, onMounted, nextTick, ref, watch } from 'vue'
import { lockScroll, unlockScroll } from '../useScrollLock'

const props = withDefaults(defineProps<{
  open: boolean
  title: string
  kicker?: string
  testid?: string
}>(), { testid: 'side-panel' })

const emit = defineEmits<{ (e: 'close'): void }>()

const PUSH_QUERY = '(min-width: 1280px)'
const panel = ref<HTMLElement | null>(null)
/** true = push 档（非模态）。SSR / 无 matchMedia 环境按非 push 处理，宁可多锁一次也不漏解锁 */
const push = ref(false)

let mql: MediaQueryList | null = null
let locked = false
let lastFocused: HTMLElement | null = null

function syncLock(): void {
  const want = props.open && !push.value
  if (want && !locked) {
    lockScroll()
    locked = true
  } else if (!want && locked) {
    unlockScroll()
    locked = false
  }
}

function onMedia(e: MediaQueryListEvent | MediaQueryList): void {
  push.value = e.matches
  syncLock()
}

async function onOpened(): Promise<void> {
  lastFocused = document.activeElement as HTMLElement | null
  await nextTick()
  panel.value?.focus()
}

/** 关闭后焦点回到触发元素（§12 无障碍清单）——否则焦点掉回 `<body>`，键盘用户得从头 Tab */
function onClosed(): void {
  lastFocused?.focus?.()
  lastFocused = null
}

onMounted(() => {
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    mql = window.matchMedia(PUSH_QUERY)
    push.value = mql.matches
    // addEventListener 在 Safari <14 不存在，退回 addListener
    if (mql.addEventListener) mql.addEventListener('change', onMedia)
    else mql.addListener(onMedia)
  }
  syncLock()
  // 调用方通常用 `v-if` 挂载本组件，于是它**挂载时就已经是打开的**——
  // 而 watch 不是 immediate，只靠 watch 会让首次打开完全没有焦点处理。
  if (props.open) void onOpened()
})

onBeforeUnmount(() => {
  if (mql) {
    if (mql.removeEventListener) mql.removeEventListener('change', onMedia)
    else mql.removeListener(onMedia)
  }
  // 组件被卸载时若还锁着，锁就再也没人解了
  if (locked) {
    unlockScroll()
    locked = false
  }
  // 同理：`v-if` 卸载就是「关闭」，焦点必须在这里还回去
  onClosed()
})

watch(() => props.open, (isOpen) => {
  syncLock()
  if (isOpen) void onOpened()
  else onClosed()
})

function focusables(): HTMLElement[] {
  if (!panel.value) return []
  return Array.from(panel.value.querySelectorAll<HTMLElement>(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  )).filter((el) => el.offsetParent !== null || el === panel.value)
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') {
    e.stopPropagation()
    emit('close')
    return
  }
  // 焦点陷阱只在**模态**档生效。push 档下列表仍可交互，把焦点困在侧板里是错的。
  if (e.key !== 'Tab' || push.value) return
  const list = focusables()
  if (!list.length) return
  const first = list[0]
  const last = list[list.length - 1]
  const active = document.activeElement
  if (e.shiftKey && (active === first || active === panel.value)) {
    e.preventDefault()
    last.focus()
  } else if (!e.shiftKey && active === last) {
    e.preventDefault()
    first.focus()
  }
}
</script>

<template>
  <div v-if="open" class="panel-root" :class="{ push }">
    <div v-if="!push" class="panel-scrim" @click="emit('close')" />
    <aside
      ref="panel"
      class="panel"
      :class="{ push }"
      tabindex="-1"
      :role="push ? 'complementary' : 'dialog'"
      :aria-modal="push ? undefined : 'true'"
      :aria-label="title"
      :data-testid="testid"
      @keydown="onKeydown"
    >
      <header class="panel-head">
        <div class="ttl">
          <span v-if="kicker" class="kicker">{{ kicker }}</span>
          <h2>{{ title }}</h2>
        </div>
        <button type="button" class="x" aria-label="关闭详情板" data-testid="side-panel-close" @click="emit('close')">×</button>
      </header>
      <div class="panel-body">
        <slot />
      </div>
      <footer v-if="$slots.footer" class="panel-foot"><slot name="footer" /></footer>
    </aside>
  </div>
</template>

<style scoped>
.panel-scrim {
  position: fixed; inset: var(--shell-topbar-h) 0 0 0;
  background: var(--scrim); z-index: calc(var(--z-panel) - 1);
}
.panel {
  display: flex; flex-direction: column;
  background: var(--bg-elev); border-left: 1px solid var(--border);
  z-index: var(--z-panel);
}
.panel:focus { outline: none; }

/* ── push（≥1280）：占据父 grid 让出的一列，sticky 跟随滚动，不遮挡列表 ── */
.panel.push {
  position: sticky; top: var(--shell-topbar-h);
  height: calc(100dvh - var(--shell-topbar-h)); overflow-y: auto;
}
/* ── overlay（1024–1279） ── */
.panel:not(.push) {
  position: fixed; right: 0; top: var(--shell-topbar-h); bottom: 0;
  width: min(94vw, 458px); overflow-y: auto; box-shadow: var(--shadow-lg), var(--shadow-lg-glow);
}
/* ── sheet（<1024）：全屏。
   用 `left:0;right:0;width:auto` 而**不是** `width:100vw`——`100vw` 含滚动条宽度，
   在有滚动条的窗口下会比视口宽，正好撞上 e2e 的「body 零横向溢出」断言。 ── */
@media (max-width: 1023px) {
  .panel:not(.push) { left: 0; right: 0; width: auto; border-left: 0; }
}

.panel-head {
  position: sticky; top: 0; z-index: 1;
  display: flex; align-items: flex-start; justify-content: space-between; gap: var(--sp-3);
  padding: var(--sp-4) var(--sp-4) var(--sp-3);
  /* 不透明底：sticky 头压在滚动内容上，半透明会串字 */
  background: var(--bg-elev); border-bottom: 1px solid var(--border);
}
.kicker { display: block; color: var(--accent); font-size: var(--fs-2xs); font-weight: var(--fw-bold); letter-spacing: .16em; text-transform: uppercase; }
.panel-head h2 { margin: 2px 0 0; font-size: var(--fs-lg); }
.x {
  flex: none; width: 30px; height: 30px; border: 1px solid var(--border); border-radius: var(--radius-sm);
  background: var(--bg-soft); color: var(--text-soft); cursor: pointer; font-size: 17px; line-height: 1;
}
.x:hover { background: var(--bg-hover); color: var(--text); }
.panel-body { flex: 1; padding: var(--sp-4); }
.panel-foot { padding: var(--sp-3) var(--sp-4); border-top: 1px solid var(--border); background: var(--bg-soft); }
@media (pointer: coarse) { .x { min-height: var(--touch-min); width: var(--touch-min); } }
</style>
