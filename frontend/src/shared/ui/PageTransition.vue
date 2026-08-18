<script setup lang="ts">
/**
 * 路由过渡包装件（UX 重设计 Phase G）：router-view + <transition mode="out-in"> 复用件。
 * 必须落到 AppShell 顶层和 ConsoleShell 嵌套出口——只挂顶层时 console 内的页间切换（tab 切换）
 * 因顶层组件实例不变而不触发过渡（评审 S2）。过渡 class .page-* 定义在 tokens.css，被全局 reduced-motion 兜底自动禁用。
 * 不 keyed：同组件不同 params（如 activity-new↔activity-edit 共用 EditorView）沿用不重挂的现有行为，避免 e2e 依赖的组件状态被重置。
 */
</script>

<template>
  <router-view v-slot="{ Component }">
    <transition name="page" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
</template>
