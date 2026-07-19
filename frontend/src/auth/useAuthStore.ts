// 认证 store —— authClient 纯模块的响应式包装 + auth-config 拉取 + header 注入注册。
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api, setHeaderProvider, setUnauthorizedHandler } from '@/shared/apiClient'
import type { AuthConfig } from '@/shared/types'
import * as authClient from './authClient'
import { useTenantStore } from '@/stores/useTenantStore'
import { useActorStore } from '@/stores/useActorStore'

export const useAuthStore = defineStore('auth', () => {
  const cfg = ref<AuthConfig | null>(null)
  const token = ref<string | null>(null)
  const refreshTok = ref<string | null>(null)
  const expiresAt = ref(0)

  const authEnabled = computed(() => !!cfg.value?.authEnabled)
  const loggedIn = computed(() => !!token.value)
  const tenant = computed(() => authClient.tokenTenant(token.value, cfg.value))
  const actor = computed(() => authClient.tokenSub(token.value))

  function applyTokenState(t: authClient.TokenState): void {
    token.value = t.token
    refreshTok.value = t.refresh
    expiresAt.value = t.expiresAt
  }

  /** 拉 auth-config（幂等），并从 sessionStorage 恢复已有 token */
  async function ensureConfig(): Promise<AuthConfig> {
    if (cfg.value) return cfg.value
    const r = await api<AuthConfig>('marketing', 'GET', '/auth-config')
    cfg.value = r.json || { authEnabled: false }
    applyTokenState(authClient.loadToken())
    return cfg.value
  }

  async function beginLogin(clientId: string, returnTo: string): Promise<void> {
    if (!cfg.value) await ensureConfig()
    await authClient.login(cfg.value as AuthConfig, clientId, returnTo)
  }

  async function completeCallback(search: string): Promise<string> {
    if (!cfg.value) await ensureConfig()
    const res = await authClient.handleCallback(cfg.value as AuthConfig, search)
    applyTokenState(res.token)
    return res.returnTo
  }

  async function ensureFresh(): Promise<void> {
    if (!authEnabled.value || !token.value) return
    if (authClient.isExpiring(expiresAt.value) && refreshTok.value) {
      try {
        applyTokenState(await authClient.refresh(cfg.value as AuthConfig, refreshTok.value))
      } catch {
        logout()
      }
    }
  }

  function logout(): void {
    authClient.clearToken()
    applyTokenState({ token: null, refresh: null, expiresAt: 0 })
  }

  // 注册 header 注入：auth 档发 Bearer（不发 X-Tenant-Id）；dev 档发 X-Tenant-Id + X-Actor（四眼）。
  const tenantStore = useTenantStore()
  const actorStore = useActorStore()
  setHeaderProvider(() => {
    const h: Record<string, string> = {}
    if (authEnabled.value) {
      if (token.value) h['Authorization'] = 'Bearer ' + token.value // 不发 X-Tenant-Id；操作者=JWT sub
    } else {
      if (tenantStore.tenant) h['X-Tenant-Id'] = tenantStore.tenant
      if (actorStore.actor) h['X-Actor'] = actorStore.actor // 四眼：dev 档操作者身份
    }
    return h
  })
  // 401 → auth 档清 token（路由守卫会把用户送回登录页）
  setUnauthorizedHandler(() => {
    if (authEnabled.value) logout()
  })

  return {
    cfg,
    token,
    authEnabled,
    loggedIn,
    tenant,
    actor,
    ensureConfig,
    beginLogin,
    completeCallback,
    ensureFresh,
    logout,
  }
})
