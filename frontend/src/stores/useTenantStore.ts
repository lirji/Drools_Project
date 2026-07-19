// dev/header 档租户（X-Tenant-Id）。auth 档不用此 store（租户由 token aud 定）。
// 沿用旧前端 localStorage key `actTenant`，平滑接续。
import { defineStore } from 'pinia'
import { ref } from 'vue'

const TENANT_KEY = 'actTenant'

function readTenant(): string {
  try {
    return localStorage.getItem(TENANT_KEY) || 'acme'
  } catch {
    return 'acme'
  }
}

export const useTenantStore = defineStore('tenant', () => {
  const tenant = ref(readTenant())

  function setTenant(t: string): void {
    tenant.value = t || ''
    try {
      localStorage.setItem(TENANT_KEY, tenant.value)
    } catch {
      /* 隐私模式忽略 */
    }
  }

  return { tenant, setTenant }
})
