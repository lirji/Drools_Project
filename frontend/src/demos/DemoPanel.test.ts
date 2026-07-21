import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { api } from '@/shared/apiClient'
import DemoPanel from './DemoPanel.vue'

vi.mock('@/shared/apiClient', () => ({ api: vi.fn() }))

describe('规则能力面板请求生命周期', () => {
  it('切换能力后忽略上一个能力的迟到响应', async () => {
    let resolveRequest!: (value: { ok: boolean; status: number; json: unknown; text: string }) => void
    vi.mocked(api).mockReturnValueOnce(new Promise((resolve) => { resolveRequest = resolve }))

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/demos', name: 'demos', component: { template: '<div />' } },
        { path: '/demos/:demoId', name: 'demo', component: { template: '<div />' } },
      ],
    })
    await router.push('/demos/discount-calculate')
    await router.isReady()

    const wrapper = mount(DemoPanel, { global: { plugins: [router] } })
    expect(wrapper.text()).toContain('规则能力中心')
    expect(wrapper.text()).toContain('能力 C02')
    expect(wrapper.text()).not.toMatch(/实验|学习|教程|Step/i)
    await wrapper.get('[data-testid="demo-run"]').trigger('click')
    expect(wrapper.get('.response-card').attributes('aria-busy')).toBe('true')

    await router.push('/demos/cart-checkout')
    await flushPromises()
    resolveRequest({ ok: true, status: 200, json: { from: 'old-demo' }, text: '' })
    await flushPromises()

    expect(wrapper.find('[data-testid="demo-panel-cart-checkout"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="demo-status"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('old-demo')
  })
})
