import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useAuthStore } from '@/auth/useAuthStore'
import EditorView from './EditorView.vue'

function response(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: vi.fn().mockResolvedValue(JSON.stringify(body)),
  } as unknown as Response
}

async function setup(authEnabled = false) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.cfg = { authEnabled }

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/console/activities', name: 'activities', component: { template: '<div />' } },
      { path: '/console/activities/new', name: 'activity-new', component: EditorView },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push('/console/activities/new')
  await router.isReady()

  const wrapper = mount({ template: '<router-view />' }, {
    global: {
      plugins: [pinia, router],
      stubs: {
        PageHeader: { template: '<header><slot /><slot name="actions" /></header>' },
        Banner: { template: '<div><slot /></div>' },
        Section: { template: '<section><slot /></section>' },
      },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('EditorView 初始化失败降级', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('字段字典暂不可用时仍展示新建表单，并阻止误保存', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    const { wrapper, router } = await setup()

    expect(router.currentRoute.value.name).toBe('activity-new')
    expect(wrapper.find('[data-testid="dict-warning"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="form-name"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('无法打开活动编辑器')
    expect(wrapper.get('[data-testid="validation-errs"]').text()).toContain('字段配置未加载，暂时不能保存')
  })

  it('认证会话失效时回到登录页并保留返回地址', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(401, { message: 'unauthorized' })))

    const { router } = await setup(true)

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.returnTo).toBe('/console/activities/new')
  })
})
