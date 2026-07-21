import { mount } from '@vue/test-utils'
import DemoShell from './DemoShell.vue'

describe('DemoShell 内容布局', () => {
  it('右侧内容区不再渲染能力导航', () => {
    const wrapper = mount(DemoShell, {
      global: { stubs: { PageTransition: true } },
    })

    expect(wrapper.find('.demos-side').exists()).toBe(false)
    expect(wrapper.find('[data-testid="demo-nav"]').exists()).toBe(false)
    expect(wrapper.find('.demos-panel').exists()).toBe(true)
  })
})
