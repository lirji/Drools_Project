<script setup lang="ts">
import { computed, watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/auth/useAuthStore'
import { useTenantStore } from '@/stores/useTenantStore'
import { useActorStore } from '@/stores/useActorStore'

const auth = useAuthStore()
const tenantStore = useTenantStore()
const actorStore = useActorStore()
const route = useRoute()
const router = useRouter()

// 登出：清 token 后导航回登录页（清 token 本身不触发路由守卫）
function doLogout(): void {
  auth.logout()
  router.push({ name: 'login' })
}

// 401 途中失效（apiClient onUnauthorized→logout 清 token）也要回登录页：监听登录态兜底导航。
watch(
  () => auth.authEnabled && !auth.loggedIn,
  (loggedOut) => {
    if (loggedOut && route.name !== 'login' && route.name !== 'callback') router.push({ name: 'login' })
  },
)

const tabs = [
  { name: 'activities', label: '活动列表', testid: 'tab-list' },
  { name: 'activity-new', label: '新建活动', testid: 'tab-new' },
  { name: 'validate', label: '优惠验证', testid: 'tab-validate' },
]
const quickTenants = ['acme', 'beta', '__dev__']
const activeTab = computed(() => {
  const n = route.name as string
  if (n === 'activity-detail' || n === 'activity-edit') return 'activities'
  return n
})
</script>

<template>
  <main class="console">
    <div class="console-head">
      <h2>活动营销 · 报表配置台</h2>
      <p class="desc">报表式创建活动 → 白名单条件树制定资格规则 → 上线 → 验证优惠命中。规则由 Drools 执行。</p>

      <!-- auth 档身份条：租户由 token aud 定，禁手动切换（信封≠aud 会 403） -->
      <div v-if="auth.authEnabled" class="id-bar" data-testid="auth-bar">
        <span class="id-label">登录租户 (token aud)</span>
        <span class="chip chip-active" data-testid="auth-tenant">{{ auth.tenant || '-' }}</span>
        <span class="id-hint">操作者 {{ auth.actor || '-' }} —— 租户由 Casdoor token 决定，切租户请登出后换账号登录</span>
        <button class="chip" data-testid="logout" @click="doLogout">登出</button>
      </div>

      <!-- dev/header 档租户切换条 -->
      <div v-else class="id-bar" data-testid="tenant-bar">
        <span class="id-label">租户 (X-Tenant-Id)</span>
        <input
          class="tenant-input"
          data-testid="tenant-input"
          :value="tenantStore.tenant"
          @change="tenantStore.setTenant(($event.target as HTMLInputElement).value.trim())"
        />
        <button
          v-for="t in quickTenants"
          :key="t"
          class="chip"
          :class="{ 'chip-active': t === tenantStore.tenant }"
          :data-testid="'tenant-chip-' + t"
          @click="tenantStore.setTenant(t)"
        >
          {{ t }}
        </button>
        <span class="id-hint">切租户即换数据视图 —— 后端 @TenantId 按此隔离</span>
      </div>

      <!-- dev 档四眼操作者（X-Actor）：上线时后端校验审批人≠提交人。留空=不发 X-Actor（四眼关时无影响） -->
      <div v-if="!auth.authEnabled" class="id-bar actor" data-testid="actor-bar">
        <span class="id-label">操作者 (X-Actor)</span>
        <input
          class="tenant-input"
          data-testid="actor-input"
          :value="actorStore.actor"
          placeholder="如 alice（四眼开启时上线需≠提交人）"
          @change="actorStore.setActor(($event.target as HTMLInputElement).value.trim())"
        />
        <span class="id-hint">四眼职责分离：谁在操作。四眼开启时，上线的审批人不能等于活动提交人。</span>
      </div>
    </div>

    <nav class="tabs">
      <router-link
        v-for="t in tabs"
        :key="t.name"
        :to="{ name: t.name }"
        class="tab"
        :class="{ active: activeTab === t.name }"
        :data-testid="t.testid"
      >
        {{ t.label }}
      </router-link>
    </nav>

    <RouterView />
  </main>
</template>

<style scoped>
.console { max-width: 1200px; margin: 0 auto; padding: var(--sp-5) var(--sp-4); }
.console-head h2 { margin: 0 0 var(--sp-2); }
.desc { color: var(--text-soft); font-size: 13px; margin: 0 0 var(--sp-3); }
.id-bar {
  display: flex; align-items: center; flex-wrap: wrap; gap: var(--sp-2);
  padding: var(--sp-3); background: var(--bg-soft);
  border: 1px solid var(--border); border-radius: var(--radius-sm);
}
.id-label { font-size: 12px; color: var(--text-soft); font-weight: 600; }
.id-hint { font-size: 12px; color: var(--text-faint); }
.tenant-input {
  padding: var(--sp-1) var(--sp-2); border: 1px solid var(--border);
  border-radius: var(--radius-sm); background: var(--bg-elev); color: var(--text);
}
.chip {
  min-height: 28px; padding: var(--sp-1) var(--sp-3);
  border: 1px solid var(--border); border-radius: 999px;
  background: var(--bg-elev); color: var(--text); font-size: 12.5px; cursor: pointer;
}
.chip-active { background: var(--accent); color: #fff; border-color: var(--accent); }
.tabs { display: flex; gap: var(--sp-2); margin: var(--sp-4) 0; border-bottom: 1px solid var(--border); }
.tab {
  padding: var(--sp-2) var(--sp-4); font-size: 13px; text-decoration: none;
  color: var(--text-soft); border-bottom: 2px solid transparent;
}
.tab.active { color: var(--accent); border-bottom-color: var(--accent); }
@media (pointer: coarse) {
  .chip { min-height: var(--touch-min); }
}
</style>
