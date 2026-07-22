import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthConfig } from '@/shared/types'
import LoginView from './LoginView.vue'

const auth = vi.hoisted(() => ({
  cfg: null as AuthConfig | null,
  ensureConfig: vi.fn(),
  beginLogin: vi.fn(),
}))

vi.mock('@/auth/useAuthStore', () => ({
  useAuthStore: () => ({
    get cfg() {
      return auth.cfg
    },
    ensureConfig: auth.ensureConfig,
    beginLogin: auth.beginLogin,
  }),
}))

const allowedConfig: AuthConfig = {
  authEnabled: true,
  webClients: [
    { tenant: 'acme', clientId: 'activity-acme-web-cid' },
    { tenant: 'beta', clientId: 'activity-beta-web-cid' },
  ],
}

async function mountLogin(path: string, settle = true) {
  const router = createRouter({
    history: createMemoryHistory('/ui/'),
    routes: [{ path: '/login', component: LoginView }],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(LoginView, {
    global: {
      plugins: [router],
      stubs: { Icon: true },
    },
  })
  if (settle) await flushPromises()
  return wrapper
}

beforeEach(() => {
  auth.cfg = allowedConfig
  auth.ensureConfig.mockReset().mockResolvedValue(allowedConfig)
  auth.beginLogin.mockReset().mockResolvedValue(undefined)
})

describe('LoginView portal auto-login', () => {
  it('使用与其他能力平台一致的双栏品牌登录结构', async () => {
    const wrapper = await mountLogin('/login')

    expect(wrapper.find('.login-brand').exists()).toBe(true)
    expect(wrapper.find('.login-form-panel').exists()).toBe(true)
    expect(wrapper.find('.login-compact-head').exists()).toBe(true)
    expect(wrapper.find('.login-primary').exists()).toBe(true)
    expect(wrapper.text()).toContain('规则编排与决策')
    expect(wrapper.text()).toContain('由 Casdoor 提供统一身份认证')
  })

  it('配置加载期间展示明确状态并禁用租户提交', async () => {
    let resolveConfig!: (value: AuthConfig) => void
    auth.ensureConfig.mockImplementation(
      () => new Promise<AuthConfig>((resolve) => { resolveConfig = resolve }),
    )

    const wrapper = await mountLogin('/login', false)
    await nextTick()
    expect(wrapper.get('[role="status"]').text()).toContain('正在加载认证配置')
    expect(wrapper.get('#login-tenant').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="login-submit"]').attributes('disabled')).toBeDefined()

    resolveConfig(allowedConfig)
    await flushPromises()
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(wrapper.get('#login-tenant').attributes('disabled')).toBeUndefined()
  })

  it('配置加载后仅为 allowlisted client 自动发起一次登录', async () => {
    await mountLogin('/login?source=portal&auto=1&clientId=activity-acme-web-cid&returnTo=%2Fconsole%2Factivities')

    expect(auth.ensureConfig).toHaveBeenCalledTimes(1)
    expect(auth.beginLogin).toHaveBeenCalledTimes(1)
    expect(auth.beginLogin).toHaveBeenCalledWith('activity-acme-web-cid', '/console/activities')
  })

  it('未知 clientId 与普通登录入口均保留租户输入页', async () => {
    const invalid = await mountLogin('/login?source=portal&auto=1&clientId=unknown')
    expect(auth.beginLogin).not.toHaveBeenCalled()
    expect(invalid.find('#login-tenant').exists()).toBe(true)
    expect(invalid.text()).toContain('当前可用租户：acme、beta')

    auth.ensureConfig.mockClear()
    const direct = await mountLogin('/login')
    expect(auth.beginLogin).not.toHaveBeenCalled()
    expect(direct.find('#login-tenant').exists()).toBe(true)
  })

  it('人工输入租户时只允许后端 webClients allowlist，并映射正确 clientId', async () => {
    const wrapper = await mountLogin('/login?returnTo=%2Fconsole%2Factivities')

    await wrapper.get('#login-tenant').setValue('unknown')
    await wrapper.get('form').trigger('submit')
    expect(auth.beginLogin).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('未知租户 unknown')

    await wrapper.get('#login-tenant').setValue('beta')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(auth.beginLogin).toHaveBeenCalledTimes(1)
    expect(auth.beginLogin).toHaveBeenCalledWith('activity-beta-web-cid', '/console/activities')
  })

  it('auth 关闭时不自动登录', async () => {
    auth.cfg = { ...allowedConfig, authEnabled: false }
    auth.ensureConfig.mockResolvedValue(auth.cfg)
    await mountLogin('/login?source=portal&auto=1&clientId=activity-acme-web-cid')
    expect(auth.beginLogin).not.toHaveBeenCalled()
  })

  it('auth-config 加载失败时显示错误且不尝试自动登录', async () => {
    auth.ensureConfig.mockRejectedValue(new Error('network unavailable'))
    const wrapper = await mountLogin('/login?source=portal&auto=1&clientId=activity-acme-web-cid')
    expect(auth.beginLogin).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('认证配置加载失败：network unavailable')
  })
})
