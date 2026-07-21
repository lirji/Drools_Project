<script setup lang="ts">
/**
 * 规则能力中心外壳：能力目录（DemoNav，次级导航，懒加载不进主包）+ 在线调用面板。
 * 重设计后本壳位于全局 AppShell 内容区内，去掉自有 max-width/居中（由 AppShell 内容区统一框住）。
 */
import DemoNav from './DemoNav.vue'
import PageTransition from '@/shared/ui/PageTransition.vue'
</script>

<template>
  <div class="demos-layout">
    <aside class="demos-side">
      <DemoNav />
    </aside>
    <section class="demos-panel">
      <PageTransition />
    </section>
  </div>
</template>

<style scoped>
.demos-layout { display: flex; gap: var(--sp-5); align-items: flex-start; }
.demos-side {
  width: 288px; flex-shrink: 0; position: sticky; top: var(--sp-4);
  max-height: calc(100dvh - var(--shell-topbar-h) - var(--sp-6)); overflow-y: auto;
  scrollbar-width: thin;
}
.demos-panel { flex: 1; min-width: 0; }
/* <1024（正典）：塌单列。768 平板下全局侧栏已 docked 占 248px，再并排 260px 二级导航会双侧栏挤压→溢出，故此处随内容多栏统一 <1024 堆叠。 */
@media (max-width: 1023px) {
  .demos-layout { flex-direction: column; gap: var(--sp-4); }
  .demos-side { width: 100%; position: static; max-height: none; }
  .demos-panel { width: 100%; }
}
</style>
