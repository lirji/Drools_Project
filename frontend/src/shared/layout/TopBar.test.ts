import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import TopBar from './TopBar.vue'

/**
 * 钉住缺陷 F9：dark-first 换代后，`data-theme` 属性只在用户显式选过主题时才存在。
 * 属性缺席 = 按 :root 裸块渲成深色；此时若把 dark 初始化成 false，按钮会显示月亮、
 * aria-pressed=false，第一次点击写入 dark 后**视觉零变化**，要点两次才切得到浅色。
 * 这是每个新用户的默认路径，必须从系统偏好反推初值。
 */
function mountTopBar() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/home', name: 'home', component: { template: '<div />' } }],
  })
  return mount(TopBar, {
    global: { plugins: [router], stubs: { Icon: true, IdentityBar: true } },
  })
}

/** jsdom 没有 matchMedia 实现，按查询串返回指定结果。 */
function stubMatchMedia(prefersLight: boolean) {
  window.matchMedia = ((q: string) => ({
    matches: q.includes('prefers-color-scheme: light') ? prefersLight : !prefersLight,
    media: q,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
    dispatchEvent: () => false,
    onchange: null,
  })) as unknown as typeof window.matchMedia
}

describe('TopBar 主题切换初值（F9）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.documentElement.removeAttribute('data-theme')
  })

  it('data-theme 缺席且系统偏好非浅色时，初值判为深色（否则首次点击视觉零变化）', async () => {
    stubMatchMedia(false)
    const wrapper = mountTopBar()
    await flushPromises()
    expect(wrapper.get('[data-testid="theme-btn"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="theme-btn"]').attributes('aria-label')).toBe('切换到浅色主题')
  })

  it('data-theme 缺席但系统显式偏好浅色时，初值判为浅色', async () => {
    stubMatchMedia(true)
    const wrapper = mountTopBar()
    await flushPromises()
    expect(wrapper.get('[data-testid="theme-btn"]').attributes('aria-pressed')).toBe('false')
  })

  it('data-theme 存在时以属性为准，忽略系统偏好', async () => {
    stubMatchMedia(true)
    document.documentElement.setAttribute('data-theme', 'dark')
    const wrapper = mountTopBar()
    await flushPromises()
    expect(wrapper.get('[data-testid="theme-btn"]').attributes('aria-pressed')).toBe('true')
  })

  it('点击一次即真正切换（属性与 aria 同步翻面）', async () => {
    stubMatchMedia(false)
    const wrapper = mountTopBar()
    await flushPromises()
    await wrapper.get('[data-testid="theme-btn"]').trigger('click')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(wrapper.get('[data-testid="theme-btn"]').attributes('aria-pressed')).toBe('false')
  })
})
