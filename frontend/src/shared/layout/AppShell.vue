<script setup lang="ts">
/**
 * 应用外壳（重设计）：组合 AppLayout + SidebarNav + TopBar + <router-view> + ToastHost。
 * - 持有抽屉开合状态；汉堡开、scrim/Esc/路由跳转关；<768 抽屉打开时锁 body 滚动。
 * - 承接 ConsoleShell 的「401 途中失效 → 兜底跳 login」watch（上提后同时覆盖 demos 区，属登出重定向的行为扩面）。
 * 登录/回调页不套本壳（由 App.vue 分流）。
 */
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from './AppLayout.vue'
import SidebarNav from './SidebarNav.vue'
import TopBar from './TopBar.vue'
import { useAuthStore } from '@/auth/useAuthStore'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const drawerOpen = ref(false)

function closeDrawer(): void {
  drawerOpen.value = false
}

// 路由跳转自动关抽屉
watch(() => route.fullPath, closeDrawer)

// 401 途中失效（apiClient onUnauthorized→logout 清 token）兜底导航回登录页
watch(
  () => auth.authEnabled && !auth.loggedIn,
  (loggedOut) => {
    if (loggedOut && route.name !== 'login' && route.name !== 'callback') router.push({ name: 'login' })
  },
)

// 抽屉开时锁 body 滚动
watch(drawerOpen, (open) => {
  document.body.style.overflow = open ? 'hidden' : ''
})

function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape') closeDrawer()
}
onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => {
  window.removeEventListener('keydown', onKey)
  document.body.style.overflow = ''
})
</script>

<template>
  <AppLayout :drawer-open="drawerOpen" @close="closeDrawer">
    <template #topbar>
      <TopBar @toggle-nav="drawerOpen = !drawerOpen" />
    </template>
    <template #sidebar>
      <SidebarNav @navigate="closeDrawer" />
    </template>
    <router-view />
  </AppLayout>
</template>
