import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import PlaybooksView from './PlaybooksView.vue'
import { PLAYBOOKS } from '../playbooks'

async function setup() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/console/playbooks', name: 'playbooks', component: PlaybooksView },
      { path: '/console/activities/new', name: 'activity-new', component: { template: '<div />' } },
    ],
  })
  await router.push('/console/playbooks')
  await router.isReady()

  const wrapper = mount({ template: '<router-view />' }, {
    global: {
      plugins: [router],
      stubs: { PageHeader: { template: '<header><slot name="actions" /></header>' } },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('玩法模板屏', () => {
  it('每个玩法都渲染成一张卡', async () => {
    const { wrapper } = await setup()
    for (const p of PLAYBOOKS) {
      expect(wrapper.find(`[data-testid="playbook-card-${p.id}"]`).exists(), p.id).toBe(true)
    }
  })

  it('不可用的玩法不给「用它新建」，但必须把缺什么写在卡上', async () => {
    const { wrapper } = await setup()
    const blocked = PLAYBOOKS.filter((p) => p.group === 'blocked')
    for (const p of blocked) {
      expect(wrapper.find(`[data-testid="playbook-use-${p.id}"]`).exists(), `${p.id} 不该有「用它新建」`).toBe(false)
      const card = wrapper.get(`[data-testid="playbook-card-${p.id}"]`)
      expect(card.text()).toContain('缺什么')
      expect(card.classes()).toContain('blocked')
    }
  })

  it('说明卡讲清「不新增后端能力」，避免被当成新玩法上线', async () => {
    const { wrapper } = await setup()
    expect(wrapper.get('[data-testid="playbooks-note"]').text()).toContain('不新增后端能力')
  })

  it('筛选 chips 只留本组的卡', async () => {
    const { wrapper } = await setup()
    await wrapper.get('[data-testid="playbook-filter-gift"]').trigger('click')
    expect(wrapper.find('[data-testid="playbook-card-gift"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="playbook-card-ladder"]').exists()).toBe(false)
  })

  it('点「用它新建」带着 playbook 参数跳到新建页', async () => {
    const { wrapper, router } = await setup()
    await wrapper.get('[data-testid="playbook-use-ladder"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('activity-new')
    expect(router.currentRoute.value.query.playbook).toBe('ladder')
  })
})
