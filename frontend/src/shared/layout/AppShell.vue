<script setup lang="ts">
/**
 * 应用外壳（重设计）：组合 AppLayout + SidebarNav + TopBar + <router-view> + ToastHost。
 * - 持有抽屉开合状态；汉堡开、scrim/Esc/路由跳转关；<768 抽屉打开时锁 body 滚动。
 * - 承接 ConsoleShell 的「401 途中失效 → 兜底跳 login」watch（上提后同时覆盖 demos 区，属登出重定向的行为扩面）。
 * 登录/回调页不套本壳（由 App.vue 分流）。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from './AppLayout.vue'
import SidebarNav from './SidebarNav.vue'
import TopBar from './TopBar.vue'
import PageTransition from '@/shared/ui/PageTransition.vue'
import { useAuthStore } from '@/auth/useAuthStore'
import { lockScroll, unlockScroll } from '@/shared/useScrollLock'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const drawerOpen = ref(false)

function closeDrawer(): void {
  drawerOpen.value = false
}

// 网格底铺在内容区（不是 body）：ListView 每 5 行一道加重线，与 44px 网格会产生莫尔纹式拍频；
// EditorView 是长表单，网格同样只添噪。**两条最重的路线上关掉网格**。
// 用显式属性而不是 `.shell-content:has(.bench)` —— :has() 在 Firefox <121 静默失效，
// 失效方式是「没反应」，是最难排查的一类（tokens.css 已记载过这条坑）。
const DENSE_ROUTES = new Set(['activities', 'activity-new', 'activity-edit'])
const gridOff = computed(() => DENSE_ROUTES.has(route.name as string))

// 路由跳转自动关抽屉
watch(() => route.fullPath, closeDrawer)

// 401 途中失效（apiClient onUnauthorized→logout 清 token）兜底导航回登录页
watch(
  () => auth.authEnabled && !auth.loggedIn,
  (loggedOut) => {
    if (loggedOut && route.name !== 'login' && route.name !== 'callback') {
      router.replace({ name: 'login', query: { returnTo: route.fullPath } })
    }
  },
)

// 抽屉开时锁 body 滚动（计数式，与 ConfirmDialog 共用不互踩）
watch(drawerOpen, (open) => {
  if (open) lockScroll()
  else unlockScroll()
})

function onKey(e: KeyboardEvent): void {
  // 抽屉开着才响应 Esc 关抽屉；否则让位给上层（ConfirmDialog 自己 addEventListener 处理 Esc）
  if (e.key === 'Escape' && drawerOpen.value) closeDrawer()
}
onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => {
  window.removeEventListener('keydown', onKey)
  if (drawerOpen.value) unlockScroll()
})
</script>

<template>
  <AppLayout :drawer-open="drawerOpen" :grid="!gridOff" @close="closeDrawer">
    <template #topbar>
      <TopBar @toggle-nav="drawerOpen = !drawerOpen" />
    </template>
    <template #sidebar>
      <SidebarNav @navigate="closeDrawer" />
    </template>
    <PageTransition />
  </AppLayout>
</template>
