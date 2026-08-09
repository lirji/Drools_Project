<script setup lang="ts">
/**
 * 生产级外壳布局原语（重设计）：顶部工具条（sticky）+ 左侧栏（≥768 docked / <768 off-canvas 抽屉）+ 内容区。
 * 纯展示：状态（抽屉开合）由父 AppShell 持有并经 props/emit 驱动，本组件只管布局与抽屉动效。
 * 断点：≥768 侧栏常驻；<768 侧栏 fixed + translateX 离屏（不改布局宽度 → 平板 768 无横向溢出），scrim 遮罩。
 */
withDefaults(defineProps<{ drawerOpen: boolean; grid?: boolean }>(), { grid: true })
defineEmits<{ (e: 'close'): void }>()
</script>

<template>
  <div class="shell">
    <header class="shell-topbar u-glass">
      <slot name="topbar" />
    </header>
    <div class="shell-body">
      <aside class="shell-sidebar" :class="{ open: drawerOpen }">
        <slot name="sidebar" />
      </aside>
      <div v-if="drawerOpen" class="scrim" @click="$emit('close')" />
      <main class="shell-content" :class="{ 'u-grid': grid }">
        <slot />
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell { min-height: 100dvh; display: flex; flex-direction: column; }
/* 玻璃条：`.u-glass`（effects.css）给底色 + blur + 1px 高光边，`@supports` 与小屏关闭都在那里统一管。
   **只有顶栏做玻璃化**——侧栏是竖向长条、面积大，玻璃化的合成开销换不来观感，且会把
   「同屏 backdrop-filter ≤2」的配额在 /demos 上直接顶穿（那里还有 hero-stats）。 */
.shell-topbar {
  position: sticky; top: 0; z-index: var(--z-sticky);
  height: var(--shell-topbar-h); flex-shrink: 0;
  border-bottom: 1px solid var(--border);
}
.shell-body { flex: 1; display: flex; align-items: flex-start; min-height: 0; }
.shell-sidebar {
  width: var(--shell-sidebar-w); flex-shrink: 0; align-self: stretch;
  position: sticky; top: var(--shell-topbar-h);
  height: calc(100dvh - var(--shell-topbar-h)); overflow-y: auto;
  background: var(--bg-elev); border-right: 1px solid var(--border);
  padding: var(--sp-4) var(--sp-3);
}
.shell-content {
  flex: 1; min-width: 0;
  width: 100%; max-width: var(--content-max); margin: 0 auto;
  padding: var(--page-gutter);
}

/* <768：侧栏转 off-canvas 抽屉（fixed + translateX，不占布局宽度） */
@media (max-width: 767px) {
  .shell-sidebar {
    position: fixed; top: var(--shell-topbar-h); left: 0; bottom: 0;
    height: auto; width: min(85vw, 320px);
    transform: translateX(-100%); transition: transform var(--dur-slow) var(--ease-out);
    z-index: var(--z-drawer); box-shadow: var(--shadow-lg);
    padding-bottom: env(safe-area-inset-bottom);
  }
  .shell-sidebar.open { transform: translateX(0); }
  .scrim {
    position: fixed; inset: var(--shell-topbar-h) 0 0 0;
    /* 原为硬编码 rgba(15,17,23,.45)——全仓唯一一处不走 token 的颜色，暗色下偏浅、压不住内容。
       现走 --scrim，浅/深各自定义（见 tokens.css 四处主题块）。 */
    background: var(--scrim); z-index: calc(var(--z-drawer) - 1);
  }
}
</style>
