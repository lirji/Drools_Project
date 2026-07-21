<script setup lang="ts">
/**
 * 规则能力中心内容外壳：完整能力目录已迁入全局 SidebarNav，本组件只保留在线调用内容出口。
 * 重设计后本壳位于全局 AppShell 内容区内，去掉自有 max-width/居中（由 AppShell 内容区统一框住）。
 */
import PageTransition from '@/shared/ui/PageTransition.vue'
</script>

<template>
  <div class="demos-layout">
    <section class="demos-panel">
      <PageTransition />
    </section>
  </div>
</template>

<style scoped>
.demos-layout, .demos-panel { width: 100%; min-width: 0; }
</style>
