import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useAuthStore } from '@/auth/useAuthStore'
import DetailView from './DetailView.vue'

/**
 * 复核屏的**形态渲染**。
 *
 * <p>这一屏是四眼里「审批人照着看」的那一屏，所以它显示错的代价和编辑器读错一样大：
 * 审批人核对的是屏幕，不是数据库。原来的分支顺序是「range 非空 → 阶梯」优先、再 `'折'`、
 * 其余一律「固定优惠金额」——于是一口价显示成「9.90 价」，而第 N 件折与随机红包
 * （它们的 range 是**对象**不是数组）会渲染出一张标题写着「按订单金额分档计算」的<b>空票据</b>。
 */

function response(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: vi.fn().mockResolvedValue(JSON.stringify(body)),
  } as unknown as Response
}

const FIELD_DICT = {
  fields: [], operators: [], logics: [],
  activityTypes: [{ code: 1, label: '红包' }, { code: 5, label: '买赠' }],
  statuses: [{ code: 1, label: '已上线' }], distributionModes: [], strategies: ['MAX'],
}

function detailOf(rule: Record<string, unknown> | null, extra: Record<string, unknown> = {}) {
  // manage 从 extra 里拆出来单独合并：若把整个 extra 展开在末尾，extra.manage 会整体
  // **覆盖**上面刚合并好的默认值（丢 activityId/时间等），fixture 的意图正好相反。
  const { manage: manageOverride, ...rest } = extra
  return {
    manage: {
      activityId: 'ACT1', activityName: '复核用例', activityType: 1, bizLine: 'mall',
      priority: 1, inventory: 100, activityStatus: 1, version: 1,
      activityStartTime: '2026-08-01T10:00:00Z', activityEndTime: '2026-08-08T10:00:00Z',
      ...(manageOverride as object || {}),
    },
    // 加价购不落 rule 行——写平面 saveRule 见 redPackage* 全空就 return
    rules: rule ? [rule] : [], conditions: [], gifts: [], bindings: [], poolRefs: [],
    ...rest,
  }
}

/** 绑定视图 stub 配置：店铺聚合 + 各页明细。默认空，绝大多数用例不关心绑定。 */
interface BindingStub {
  stores?: Array<Record<string, unknown>>
  spusByPage?: Record<number, Record<string, unknown>>
}

async function setup(rule: Record<string, unknown> | null, extra: Record<string, unknown> = {}, binding: BindingStub = {}) {
  const stores = binding.stores ?? []
  const spusByPage = binding.spusByPage ?? {}
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
    const u = String(url)
    if (u.includes('/field-dict')) return Promise.resolve(response(200, FIELD_DICT))
    if (u.includes('/binding-stores')) return Promise.resolve(response(200, stores))
    if (u.includes('/binding-spus')) {
      const page = Number(new URL(u, 'http://localhost').searchParams.get('page') ?? 0)
      return Promise.resolve(response(200, spusByPage[page] ?? { total: 0, page, size: 10, items: [] }))
    }
    return Promise.resolve(response(200, detailOf(rule, extra)))
  }))

  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().cfg = { authEnabled: false }

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/console/activities', name: 'activities', component: { template: '<div />' } },
      { path: '/console/activities/:id', name: 'activity-detail', component: DetailView },
      { path: '/console/activities/:id/edit', name: 'activity-edit', component: { template: '<div />' } },
    ],
  })
  await router.push('/console/activities/ACT1')
  await router.isReady()

  const wrapper = mount({ template: '<router-view />' }, {
    global: {
      plugins: [pinia, router],
      stubs: {
        PageHeader: { template: '<header><slot /><slot name="actions" /></header>' },
        Banner: { template: '<div><slot /></div>' },
        Card: { template: '<section><slot /></section>' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('DetailView 权益形态渲染', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  it('一口价显示成「卖多少」，不是「减多少」', async () => {
    const wrapper = await setup({ redPackageAmountUnit: '价', redPackageAmount: 9.9, redPackageTakeType: 1 })

    const card = wrapper.get('[data-testid="detail-price"]')
    expect(card.text()).toContain('一口价')
    expect(card.text()).toContain('就卖这个数')
    // 旧行为是「固定优惠金额 9.90 价」——把「卖 9.9」讲成了「减 9.9」
    expect(wrapper.text()).not.toContain('固定优惠金额')
  })

  it('第 N 件折显示第几件与折数，不渲染成空的阶梯票据', async () => {
    const wrapper = await setup({
      redPackageAmountUnit: '件折', redPackageAmount: 5,
      redPackageRangeAmount: '{"nth":3}', redPackageTakeType: 1,
    })

    expect(wrapper.get('[data-testid="detail-nth"]').text()).toContain('第 3 件')
    expect(wrapper.text()).not.toContain('按订单金额分档计算')
  })

  it('nth 缺失/非法时明说「决策侧会判定不适用」，而不是装作配好了', async () => {
    const wrapper = await setup({
      redPackageAmountUnit: '件折', redPackageAmount: 5,
      redPackageRangeAmount: '{}', redPackageTakeType: 1,
    })
    expect(wrapper.get('[data-testid="detail-nth"]').text()).toContain('不适用')
  })

  it('随机红包显示区间，不渲染成空的阶梯票据', async () => {
    const wrapper = await setup({
      redPackageAmountUnit: '元', redPackageAmount: 10,
      redPackageRangeAmount: '{"min":5,"max":20}', redPackageTakeType: 2,
    })

    const card = wrapper.get('[data-testid="detail-random"]')
    expect(card.text()).toContain('5.00')
    expect(card.text()).toContain('20.00')
    expect(card.text()).toContain('确定性随机')
    expect(wrapper.text()).not.toContain('按订单金额分档计算')
  })

  it('随机区间脏数据仍归 random，但显示损坏提示且不因非空断言崩页', async () => {
    const wrapper = await setup({
      redPackageAmountUnit: '元', redPackageAmount: null,
      redPackageRangeAmount: '{"min":5,"max":1}', redPackageTakeType: 2,
    })

    expect(wrapper.get('[data-testid="detail-random"]').text()).toContain('随机区间数据损坏')
    expect(wrapper.get('[data-testid="detail-benefit-form"]').text()).toContain('随机金额')
  })

  it('损坏的非空阶梯仍归 ladder，不回落固定金额或渲染空票据', async () => {
    const wrapper = await setup({
      redPackageAmountUnit: '元', redPackageAmount: null,
      redPackageRangeAmount: '[{"min":0}]', redPackageTakeType: 1,
    })

    expect(wrapper.text()).toContain('档位数据损坏')
    expect(wrapper.text()).not.toContain('固定优惠金额')
  })

  it('加价购没有 rule 行也不显示「没有红包规则配置」，换购品按加价额讲清楚', async () => {
    const wrapper = await setup(null, {
      manage: { activityType: 6 },
      gifts: [{ giftName: '品牌保温杯', giftNum: 1, absoluteAmount: 9.9, rightType: 'ADD_ON' }],
    })

    expect(wrapper.get('[data-testid="detail-addon"]').text()).toContain('换购')
    // 这一屏此前会说「没有红包规则配置」——看起来像运营漏配了，实际是这个玩法压根不走红包字段
    expect(wrapper.text()).not.toContain('没有红包规则配置')
    expect(wrapper.text()).toContain('加价购换购品')
    expect(wrapper.get('[data-testid="detail-gift-row"]').text()).toContain('加 9.90 元')
    // 金额含义必须写明：同样一个 9.90，买赠是赠品价值，加价购是用户再掏的钱
    expect(wrapper.text()).toContain('不是赠品价值')
  })

  it('阶梯与折扣的渲染不受影响（旧行为零变更）', async () => {
    const ladder = await setup({
      redPackageAmountUnit: '元', redPackageAmount: null, redPackageTakeType: 1,
      redPackageRangeAmount: '[{"min":300,"max":600,"reward":50}]',
    })
    expect(ladder.text()).toContain('按订单金额分档计算')

    vi.unstubAllGlobals()
    const ratio = await setup({
      redPackageAmountUnit: '折', redPackageAmount: 8, redPackageMaxDiscount: 50, redPackageTakeType: 1,
    })
    expect(ratio.get('[data-testid="detail-ratio"]').text()).toContain('8')
    expect(ratio.get('[data-testid="detail-ratio"]').text()).toContain('最多减 50.00 元')
  })
})

describe('DetailView 商品绑定：店铺聚合 + 点击下钻', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  const spuCalls = () => (globalThis.fetch as unknown as { mock: { calls: unknown[][] } })
    .mock.calls.map((c) => String(c[0])).filter((u) => u.includes('/binding-spus'))

  it('挂载即拉店铺聚合，渲染店铺行与每店计数（不再全量下发扁平列表）', async () => {
    const wrapper = await setup({ redPackageAmountUnit: '元', redPackageAmount: 10, redPackageTakeType: 1 }, {}, {
      stores: [
        { storeId: 10, spuCount: 5, effectiveCount: 4 },
        { storeId: 20, spuCount: 2, effectiveCount: 2 },
        { storeId: null, spuCount: 1, effectiveCount: 1 },
      ],
    })

    const s10 = wrapper.get('[data-testid="binding-store-10"]')
    expect(s10.text()).toContain('店铺 #10')
    expect(s10.text()).toContain('5 件 · 4 生效')
    // null 店铺归「未指定门店」桶
    expect(wrapper.get('[data-testid="binding-store-__null__"]').text()).toContain('未指定门店')
    // 首屏不应打下钻端点
    expect(spuCalls()).toHaveLength(0)
  })

  it('点“查看”才触发下钻 fetch，且带对 storeId', async () => {
    const wrapper = await setup({ redPackageAmountUnit: '元', redPackageAmount: 10, redPackageTakeType: 1 }, {}, {
      stores: [{ storeId: 10, spuCount: 2, effectiveCount: 2 }],
      spusByPage: { 0: { total: 2, page: 0, size: 10, items: [
        { spuId: 6001, spuName: '蓝牙耳机', price: 120, bindSource: 1, effective: 1, poolId: 1 },
        { spuId: 6002, spuName: null, price: null, bindSource: 0, effective: 0, poolId: null },
      ] } },
    })

    expect(spuCalls()).toHaveLength(0)
    await wrapper.get('[data-testid="binding-store-10"] .store-row').trigger('click')
    await flushPromises()

    const calls = spuCalls()
    expect(calls.length).toBeGreaterThan(0)
    expect(calls[0]).toContain('storeId=10')
    // 商品名批量补：有档的显示名，没档的回退裸 SPU 编号
    expect(wrapper.get('[data-testid="binding-spu-6001"]').text()).toContain('蓝牙耳机')
    expect(wrapper.get('[data-testid="binding-spu-6002"]').text()).toContain('SPU 6002')
    // 失效行标失效
    expect(wrapper.get('[data-testid="binding-spu-6002"]').text()).toContain('失效')
  })

  it('翻页请求下一页（page 递增）', async () => {
    const wrapper = await setup({ redPackageAmountUnit: '元', redPackageAmount: 10, redPackageTakeType: 1 }, {}, {
      stores: [{ storeId: 10, spuCount: 15, effectiveCount: 15 }],
      spusByPage: {
        0: { total: 15, page: 0, size: 10, items: [{ spuId: 7001, spuName: '第一页', price: 9, bindSource: 1, effective: 1, poolId: 1 }] },
        1: { total: 15, page: 1, size: 10, items: [{ spuId: 7011, spuName: '第二页', price: 9, bindSource: 1, effective: 1, poolId: 1 }] },
      },
    })

    await wrapper.get('[data-testid="binding-store-10"] .store-row').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('第一页')

    await wrapper.get('[data-testid="binding-spu-next"]').trigger('click')
    await flushPromises()
    expect(spuCalls().some((u) => u.includes('page=1'))).toBe(true)
    expect(wrapper.text()).toContain('第二页')
  })

  it('没有绑定商品时显示空态', async () => {
    const wrapper = await setup({ redPackageAmountUnit: '元', redPackageAmount: 10, redPackageTakeType: 1 }, {}, { stores: [] })
    expect(wrapper.text()).toContain('没有绑定商品')
  })
})
