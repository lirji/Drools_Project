import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import SidebarNav from './SidebarNav.vue'

describe('SidebarNav 信息层级', () => {
  it('只展示活动管理相关入口', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/home', name: 'home', component: { template: '<div />' } },
        { path: '/console/activities', name: 'activities', component: { template: '<div />' } },
        { path: '/console/playbooks', name: 'playbooks', component: { template: '<div />' } },
        { path: '/console/activities/new', name: 'activity-new', component: { template: '<div />' } },
        { path: '/console/validate', name: 'validate', component: { template: '<div />' } },
      ],
    })
    await router.push('/console/activities')
    await router.isReady()

    const wrapper = mount(SidebarNav, {
      global: { plugins: [router], stubs: { Icon: true } },
    })
    const consoleEntry = wrapper.get('[data-testid="nav-console"]')

    expect(consoleEntry.classes()).toContain('group-link')
    expect(consoleEntry.text()).toBe('控制台')
    expect(wrapper.text()).toContain('活动列表')
    expect(wrapper.text()).toContain('玩法模板')
    expect(wrapper.text()).toContain('优惠验证')
    expect(wrapper.text()).not.toMatch(/规则能力中心|能力目录|Demo|教学|教程|演示/i)
  })
})
