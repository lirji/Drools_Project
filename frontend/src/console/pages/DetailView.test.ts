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

async function setup(rule: Record<string, unknown> | null, extra: Record<string, unknown> = {}) {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) =>
    Promise.resolve(response(200, String(url).includes('/field-dict') ? FIELD_DICT : detailOf(rule, extra)))))

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
