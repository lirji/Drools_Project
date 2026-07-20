<script setup lang="ts">
/**
 * 应用根（重设计）：按路由分流外壳——应用路由套 <AppShell>（持久左侧栏 + 顶部工具条 + 内容区），
 * login/callback 裸渲染（无壳登录页）。用 router.isReady() 门控，避免冷启动 route.name 未定时误闪外壳/dev 身份条（评审 I5）。
 * ToastHost 全局常挂一份（含登录/回调页）。主题切换已迁入 TopBar。
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from '@/shared/layout/AppShell.vue'
import ToastHost from '@/shared/ui/ToastHost.vue'

const route = useRoute()
const router = useRouter()
const ready = ref(false)
// 初始导航「落定」后再决定套壳/裸壳（避免冷启动 route.name 未定误闪外壳，评审 I5）。
// 用「resolve 与 reject 都置 ready」——即便首跳因后端不可达而 guard 抛错，也降级为「渲染外壳 + 页面错误态」，
// 而非整屏白（对齐旧 App 的行为）。
const done = (): void => {
  ready.value = true
}
router.isReady().then(done, done)
const isBare = computed(() => route.name === 'login' || route.name === 'callback')
</script>

<template>
  <template v-if="ready">
    <AppShell v-if="!isBare" />
    <router-view v-else />
  </template>
  <ToastHost />
</template>
