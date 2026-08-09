import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import SidebarNav from './SidebarNav.vue'

describe('SidebarNav 信息层级', () => {
  it('规则能力中心与控制台都是一级菜单', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/home', name: 'home', component: { template: '<div />' } },
        { path: '/console/activities', name: 'activities', component: { template: '<div />' } },
        { path: '/console/playbooks', name: 'playbooks', component: { template: '<div />' } },
        { path: '/console/activities/new', name: 'activity-new', component: { template: '<div />' } },
        { path: '/console/validate', name: 'validate', component: { template: '<div />' } },
        { path: '/demos', name: 'demos', component: { template: '<div />' } },
        { path: '/demos/:demoId', name: 'demo', component: { template: '<div />' } },
      ],
    })
    await router.push('/demos')
    await router.isReady()

    const wrapper = mount(SidebarNav, {
      global: { plugins: [router], stubs: { Icon: true } },
    })
    await flushPromises()
    const consoleEntry = wrapper.get('[data-testid="nav-console"]')
    const capabilityEntry = wrapper.get('[data-testid="nav-demos"]')

    expect(consoleEntry.classes()).toContain('group-link')
    expect(capabilityEntry.classes()).toContain('group-link')
    expect(consoleEntry.text()).toBe('控制台')
    expect(capabilityEntry.text()).toBe('规则能力中心')
    expect(wrapper.text()).toContain('能力目录')
    expect(wrapper.text()).not.toContain('决策能力')
    expect(wrapper.find('.side [data-testid="demo-nav"]').exists()).toBe(true)
  })
})
