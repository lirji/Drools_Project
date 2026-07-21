<script setup lang="ts">
/**
 * 控制台区外壳（重设计后瘦身）：原先的三条身份条 / 三 tab / 401-watch / doLogout 已迁入全局 AppShell
 * （SidebarNav + IdentityBar + AppShell），本组件仅作 /console 子路由的 outlet。逐页标题/工具条由各页 PageHeader 提供。
 * Phase G：嵌套出口套 PageTransition——console 页间切换（tab-list↔tab-new↔detail↔validate）走这一层，顶层 AppShell 过渡不覆盖（评审 S2）。
 */
import PageTransition from '@/shared/ui/PageTransition.vue'
</script>

<template>
  <PageTransition />
</template>
