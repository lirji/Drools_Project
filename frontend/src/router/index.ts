import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/auth/useAuthStore'

// 路由在 base /ui/ 下（createWebHistory(import.meta.env.BASE_URL)），实际 URL 为 /ui/console 等。
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/home' },
  { path: '/home', name: 'home', component: () => import('@/home/HomeView.vue') },
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
  { path: '/auth/callback', name: 'callback', component: () => import('@/views/CallbackView.vue') },
  {
    path: '/console',
    component: () => import('@/console/ConsoleShell.vue'),
    children: [
      { path: '', redirect: '/console/activities' },
      { path: 'activities', name: 'activities', component: () => import('@/console/pages/ListView.vue') },
      { path: 'playbooks', name: 'playbooks', component: () => import('@/console/pages/PlaybooksView.vue') },
      { path: 'activities/new', name: 'activity-new', component: () => import('@/console/pages/EditorView.vue') },
      { path: 'activities/:id', name: 'activity-detail', component: () => import('@/console/pages/DetailView.vue') },
      { path: 'activities/:id/edit', name: 'activity-edit', component: () => import('@/console/pages/EditorView.vue') },
      { path: 'validate', name: 'validate', component: () => import('@/console/pages/ValidateView.vue') },
    ],
  },
  {
    path: '/demos',
    component: () => import('@/demos/DemoShell.vue'),
    children: [
      { path: '', name: 'demos', component: () => import('@/demos/DemoHome.vue') },
      { path: ':demoId', name: 'demo', component: () => import('@/demos/DemoPanel.vue') },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/home' },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  // 切页滚顶；浏览器后退/前进恢复原滚动位（配合 Phase G 路由过渡，避免长页切换后停在旧位）。
  scrollBehavior(_to, _from, saved) {
    return saved || { top: 0 }
  },
})

// 路由守卫：auth 档未登录 → /login（记 returnTo）；每跳前 silent refresh。
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.ensureConfig()
  await auth.ensureFresh()
  const isAuthRoute = to.name === 'login' || to.name === 'callback'
  if (auth.authEnabled && !auth.loggedIn && !isAuthRoute) {
    return { name: 'login', query: { returnTo: to.fullPath } }
  }
  return true
})

export default router
