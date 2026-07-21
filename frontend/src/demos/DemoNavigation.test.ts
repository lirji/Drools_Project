import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import DemoHome from './DemoHome.vue'
import DemoNav from './DemoNav.vue'

const RouterLinkStub = {
  template: '<a><slot /></a>',
}

describe('规则能力中心分组导航', () => {
  it('目录页可以按关键词和能力分组筛选', async () => {
    const wrapper = mount(DemoHome, {
      global: { stubs: { RouterLink: RouterLinkStub, Icon: true } },
    })

    expect(wrapper.text()).toContain('规则能力中心')
    expect(wrapper.text()).not.toMatch(/实验|学习|教程|Step/i)
    expect(wrapper.findAll('[data-testid^="demo-home-"]')).toHaveLength(33)

    await wrapper.get('[data-testid="demo-search"]').setValue('CEP')
    expect(wrapper.find('[data-testid="demo-home-fraud-check"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="demo-home-hello"]').exists()).toBe(false)

    await wrapper.get('[data-testid="demo-search"]').setValue('')
    const eventFilter = wrapper.findAll('.filters button').find((button) => button.text().includes('实时事件'))
    expect(eventFilter).toBeDefined()
    await eventFilter!.trigger('click')
    expect(wrapper.findAll('[data-testid^="demo-home-"]')).toHaveLength(2)
  })

  it('详情导航自动展开当前分组，并能跨分组搜索', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/demos', name: 'demos', component: { template: '<div />' } },
        { path: '/demos/:demoId', name: 'demo', component: { template: '<div />' } },
      ],
    })
    await router.push('/demos/discount-calculate')
    await router.isReady()

    const wrapper = mount(DemoNav, {
      global: { plugins: [router], stubs: { RouterLink: RouterLinkStub, Icon: true } },
    })

    expect(wrapper.text()).toContain('规则能力中心')
    expect(wrapper.text()).not.toMatch(/实验|学习|教程|Step/i)
    expect(wrapper.get('[data-testid="demo-nav-discount-calculate"]').classes()).toContain('active')
    expect(wrapper.get('[data-testid="demo-nav-discount-calculate"]').isVisible()).toBe(true)

    await wrapper.get('.nav-search input').setValue('Prometheus')
    expect(wrapper.find('[data-testid="demo-nav-metrics-prometheus"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="demo-nav-hello"]').exists()).toBe(false)
  })
})
