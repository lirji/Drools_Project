import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ListView from './ListView.vue'

/**
 * 工作台的组件级回归（PR-5）。ListView 此前**没有任何单测**，
 * `vue-tsc` 是唯一的编译期关卡——而这里三条都是类型检查看不出来的错。
 */

const DAY = 86_400_000
const NOW = Date.now()

function listRow(p: Record<string, unknown>) {
  return {
    activityName: '活动名',
    bizLine: 'mall',
    activityType: 1,
    activityStatus: 0,
    version: 1,
    activityStartTime: new Date(NOW - DAY).toISOString(),
    activityEndTime: new Date(NOW + DAY).toISOString(),
    inventory: null,
    ...p,
  }
}

const FIELD_DICT = {
  fields: [], operators: [], logics: [],
  activityTypes: [{ code: 1, label: '红包' }],
  statuses: [
    { code: 0, label: '待上线' }, { code: 1, label: '已上线' },
    { code: 2, label: '已下线' }, { code: 3, label: '待生效' },
  ],
  distributionModes: [], strategies: [],
}

function stubFetch(rows: unknown[], detail: unknown = null) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const path = String(url)
    const body = path.includes('/field-dict') ? FIELD_DICT : path.includes('/list') ? rows : detail
    return {
      ok: true,
      status: 200,
      text: vi.fn().mockResolvedValue(JSON.stringify(body)),
    } as unknown as Response
  }))
}

async function setup(rows: unknown[], detail: unknown = null) {
  stubFetch(rows, detail)
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/console/activities', name: 'activities', component: ListView },
      { path: '/console/activities/new', name: 'activity-new', component: { template: '<div />' } },
      { path: '/console/activities/:id', name: 'activity-detail', component: { template: '<div />' } },
      { path: '/console/activities/:id/edit', name: 'activity-edit', component: { template: '<div />' } },
    ],
  })
  await router.push('/console/activities')
  await router.isReady()

  const wrapper = mount({ template: '<router-view />' }, {
    global: {
      plugins: [pinia, router],
      stubs: {
        PageHeader: { template: '<header><slot name="actions" /></header>' },
        SidePanel: { template: '<aside><slot /><slot name="footer" /></aside>' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ListView 工作台', () => {
  it('同一活动的线上版与草稿版只渲染一行，testid 不重复', async () => {
    const wrapper = await setup([
      listRow({ activityId: 'ACT1', version: 2, activityStatus: 0 }),
      listRow({ activityId: 'ACT1', version: 1, activityStatus: 1 }),
    ])

    // 归并前这里会是 2 —— 重复的 :key 与重复的 activity-row-ACT1
    expect(wrapper.findAll('[data-testid="activity-row-ACT1"]')).toHaveLength(1)
    // 展示的是正在服务的 v1，并标出压在它上面的草稿 v2
    const row = wrapper.get('[data-testid="activity-row-ACT1"]')
    expect(row.text()).toContain('ACT1 · v1')
    expect(row.text()).toContain('草稿 v2')
  })

  it('未来开始的草稿给出定时上线入口，预约态可取消', async () => {
    const wrapper = await setup([
      listRow({
        activityId: 'FUTURE', version: 1, activityStatus: 0,
        activityStartTime: new Date(NOW + DAY).toISOString(),
        activityEndTime: new Date(NOW + 2 * DAY).toISOString(),
      }),
      listRow({
        activityId: 'SCHEDULED', version: 1, activityStatus: 3,
        activityStartTime: new Date(NOW + DAY).toISOString(),
        activityEndTime: new Date(NOW + 2 * DAY).toISOString(),
      }),
    ])

    expect(wrapper.get('[data-testid="status-action-FUTURE"]').text()).toContain('定时上线')
    expect(wrapper.get('[data-testid="activity-row-SCHEDULED"]').text()).toContain('定时中')
    expect(wrapper.get('[data-testid="status-action-SCHEDULED"]').text()).toContain('取消定时')
  })

  it('线上版与预约切版并存时展示预约版本，并提供独立取消入口', async () => {
    const wrapper = await setup([
      listRow({ activityId: 'SWITCH', version: 1, activityStatus: 1 }),
      listRow({
        activityId: 'SWITCH', version: 2, activityStatus: 3,
        activityStartTime: new Date(NOW + DAY).toISOString(),
        activityEndTime: new Date(NOW + 2 * DAY).toISOString(),
      }),
    ])
    const row = wrapper.get('[data-testid="activity-row-SWITCH"]')
    expect(row.text()).toContain('SWITCH · v1')
    expect(row.text()).toContain('定时 v2')
    expect(wrapper.get('[data-testid="status-action-SWITCH"]').text()).toContain('下线')
    expect(wrapper.get('[data-testid="cancel-scheduled-SWITCH"]').text()).toContain('取消定时 v2')
  })

  it('勾选行后压出批量操作条并给出计数', async () => {
    const wrapper = await setup([
      listRow({ activityId: 'A', version: 1 }),
      listRow({ activityId: 'B', version: 1 }),
    ])
    expect(wrapper.find('[data-testid="bulk-bar"]').exists()).toBe(false)

    await wrapper.get('[data-testid="row-check-A"]').trigger('change')
    expect(wrapper.get('[data-testid="bulk-count"]').text()).toContain('1')

    await wrapper.get('[data-testid="select-page"]').trigger('change')
    expect(wrapper.get('[data-testid="bulk-count"]').text()).toContain('2')
  })

  it('筛选变化后，看不见的行必须从选中集里掉出去', async () => {
    const wrapper = await setup([
      listRow({ activityId: 'ON', version: 1, activityStatus: 1, activityName: '在线活动' }),
      listRow({ activityId: 'OFF', version: 1, activityStatus: 2, activityName: '下线活动' }),
    ])

    await wrapper.get('[data-testid="select-page"]').trigger('change')
    expect(wrapper.get('[data-testid="bulk-count"]').text()).toContain('2')

    await wrapper.get('[data-testid="list-status-filter"]').setValue(1)
    await flushPromises()
    // 只剩一个可见 → 选中集必须收敛到 1，否则会把屏幕上看不到的活动一起下线
    expect(wrapper.get('[data-testid="bulk-count"]').text()).toContain('1')
  })

  it('搜索关键词**不得**回显进 list-view —— 跨租户隔离断言读的就是这块文本', async () => {
    const wrapper = await setup([listRow({ activityId: 'ACT1', version: 1, activityName: '双十一预热' })])

    const input = wrapper.get('[data-testid="list-search"]')
    await input.setValue('别家租户的活动名')
    await flushPromises()

    expect(wrapper.find('[data-testid="list-empty"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="list-view"]').text()).not.toContain('别家租户的活动名')
  })

  it('决策指标缺口以说明卡记账，不画假图，也不把已存在的端点说成待建', async () => {
    const wrapper = await setup([listRow({ activityId: 'ACT1', version: 1 })])
    const notice = wrapper.get('[data-testid="metrics-notice"]')
    expect(notice.text()).toContain('决策指标尚未接入本页')
    expect(notice.text()).toContain('GET /decision/v1/metrics')
    // 这两个端点早就存在于 DecisionPlaneController，说明卡曾把它们写成「待建」——
    // 仓库里唯一一处会主动说假话的文案。别让它回去。
    expect(notice.text()).not.toContain('待建')
  })

  it('详情侧板从 rule 导出权益形态，不只显示笼统的红包类型', async () => {
    const wrapper = await setup(
      [listRow({ activityId: 'ACT1', version: 1 })],
      {
        manage: { version: 1 },
        rules: [{ redPackageAmountUnit: '价', redPackageAmount: 9.9, redPackageTakeType: 1 }],
        conditions: [], bindings: [], gifts: [],
      },
    )

    await wrapper.get('[data-testid="activity-row-ACT1"] .activity-name').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="panel-benefit-form"]').text()).toContain('一口价')
  })

  // 详情端点取的是**最高未删除版**（草稿优先，编辑器要的就是它），而列表行是**正在服务的 ONLINE 版**——
  // 两套定义不同名。侧板不说破的话，运营会以为看到的就是正在发钱的那份配置。
  it('详情是草稿版而列表行是线上版时，侧板必须挑明版本落差', async () => {
    const wrapper = await setup(
      [
        listRow({ activityId: 'ACT1', version: 2, activityStatus: 0 }),
        listRow({ activityId: 'ACT1', version: 1, activityStatus: 1 }),
      ],
      {
        manage: { version: 2 },
        rules: [{ redPackageAmountUnit: '元', redPackageAmount: 10, redPackageTakeType: 1 }],
        conditions: [], bindings: [], gifts: [], poolRefs: [], servingVersion: 1,
      },
    )

    await wrapper.get('[data-testid="activity-row-ACT1"] .activity-name').trigger('click')
    await flushPromises()

    const panel = wrapper.get('[data-testid="panel-detail"]')
    expect(panel.find('.banner-warn').exists()).toBe(true)
    expect(panel.text()).toContain('最新版本 v2')
    expect(panel.text()).toContain('正在服务的 v1')
  })

  it('详情版本与列表行一致时不弹版本落差提示（免得每次点开都是警告）', async () => {
    const wrapper = await setup(
      [listRow({ activityId: 'ACT1', version: 1, activityStatus: 1 })],
      {
        manage: { version: 1 },
        rules: [{ redPackageAmountUnit: '元', redPackageAmount: 10, redPackageTakeType: 1 }],
        conditions: [], bindings: [], gifts: [], poolRefs: [], servingVersion: 1,
      },
    )

    await wrapper.get('[data-testid="activity-row-ACT1"] .activity-name').trigger('click')
    await flushPromises()

    const panel = wrapper.get('[data-testid="panel-detail"]')
    expect(panel.find('.banner-warn').exists()).toBe(false)
    expect(panel.text()).not.toContain('正在服务的 v')
  })
})
