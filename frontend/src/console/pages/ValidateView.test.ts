import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import {
  currentGeneration,
  queryAddOnOptions,
  queryGifts,
  quoteAddOn,
  snapshotDiagnostics,
  spuDiscount,
} from '../activityApi'
import { PLAYBOOKS } from '../playbooks'
import type {
  AddOnOptionsResponse,
  AddOnQuoteResponse,
  ApiResult,
  DiscountDecisionResponse,
  GiftDecisionResponse,
} from '@/shared/types'
import ValidateView from './ValidateView.vue'

vi.mock('../activityApi', () => ({
  spuDiscount: vi.fn(),
  queryGifts: vi.fn(),
  queryAddOnOptions: vi.fn(),
  quoteAddOn: vi.fn(),
  // 诊断类调用是**解释性**的，不参与判定，所以默认桩成空回执即可。
  // 但它们必须出现在这个整模块 mock 里——漏一个就是一片 Unhandled Rejection，
  // 而用例本身照样绿（错误发生在 fire-and-forget 的 void 调用里）。
  snapshotDiagnostics: vi.fn(() => Promise.resolve({ ok: true, status: 200, json: null, text: '' })),
  currentGeneration: vi.fn(() => Promise.resolve({ ok: true, status: 200, json: null, text: '' })),
}))

const DISCOUNT_MISS: DiscountDecisionResponse = {
  hit: false,
  hitActivityId: null,
  hitActivityName: null,
  hitAmount: 0,
  strategy: 'MAX',
  hitVersion: null,
  clamped: false,
  decisionId: 'test-decision-miss',
  items: [],
  traces: ['无候选活动'],
  mode: 'rule-engine',
}

function ok<T>(json: T): ApiResult<T> {
  return { ok: true, status: 200, json, text: JSON.stringify(json) }
}

function mountView(): VueWrapper {
  return mount(ValidateView, { global: { stubs: { RouterLink: true } } })
}

async function chooseScenario(wrapper: VueWrapper, id: string): Promise<void> {
  await wrapper.get('[data-testid="v-scenario"]').setValue(id)
}

async function fillSpu(wrapper: VueWrapper, value = '990011'): Promise<void> {
  await wrapper.get('[data-testid="v-spu"]').setValue(value)
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('ValidateView', () => {
  it('由 12 个玩法派生场景并额外提供 random，场景文案不承诺命中', () => {
    const wrapper = mountView()
    const options = wrapper.findAll('[data-testid="v-scenario"] option')

    expect(options).toHaveLength(PLAYBOOKS.length + 1)
    expect(options.map((option) => option.attributes('value'))).toEqual([
      ...PLAYBOOKS.map((playbook) => playbook.id),
      'random',
    ])
    expect(wrapper.get('[data-testid="v-scenario-note"]').text()).toContain('不指定活动、也不保证命中')
    expect(wrapper.findAll('.mode-picker > button')).toHaveLength(3)
  })

  it('普通模式发送类型化汇总请求，且 lines 明确为 null', async () => {
    vi.mocked(spuDiscount).mockResolvedValueOnce(ok({
      hit: true,
      hitActivityId: 'ACT-1',
      hitActivityName: '满减活动',
      hitAmount: 20,
      strategy: 'MAX',
      hitVersion: 1,
      clamped: false,
      decisionId: 'test-decision',
      items: [],
      traces: ['eligible: ACT-1'],
      mode: 'rule-engine',
    }))
    const wrapper = mountView()
    await fillSpu(wrapper, '990011, 990012')
    await wrapper.get('[data-testid="v-order-amount"]').setValue('300.5')
    await wrapper.get('[data-testid="v-quantity"]').setValue('2')
    await wrapper.get('[data-testid="v-user"]').setValue('42')
    await wrapper.get('[data-testid="v-district"]').setValue('310000')
    await wrapper.get('[data-testid="v-tags"]').setValue('vip, new')
    await wrapper.get('[data-testid="v-store"]').setValue('7')
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    await flushPromises()

    expect(vi.mocked(spuDiscount)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(spuDiscount).mock.calls[0][0]).toEqual({
      spuIdList: [990011, 990012],
      userId: 42,
      userDistrictId: '310000',
      userTags: ['vip', 'new'],
      orderAmount: 300.5,
      quantity: 2,
      storeId: 7,
      lines: null,
    })
    expect(vi.mocked(spuDiscount).mock.calls[0][1]).toBeInstanceOf(AbortSignal)
    expect(wrapper.get('[data-testid="validate-result"]').text()).toContain('满减活动')
    expect(wrapper.text()).toContain('eligible: ACT-1')
  })

  it('拒绝缺失、残缺、NaN/非正的普通输入，不发送任何通道请求', async () => {
    const wrapper = mountView()

    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    expect(wrapper.get('[data-testid="v-error"]').text()).toContain('至少填写一个 SPU')

    await fillSpu(wrapper, '990011,abc')
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    expect(wrapper.get('[data-testid="v-error"]').text()).toContain('有限正整数')

    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-order-amount"]').setValue('-1')
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    expect(wrapper.get('[data-testid="v-error"]').text()).toContain('订单金额必须是有限正数')

    await wrapper.get('[data-testid="v-order-amount"]').setValue('100')
    await wrapper.get('[data-testid="v-quantity"]').setValue('1.5')
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    expect(wrapper.get('[data-testid="v-error"]').text()).toContain('商品数量必须是正整数')

    expect(spuDiscount).not.toHaveBeenCalled()
    expect(queryGifts).not.toHaveBeenCalled()
    expect(queryAddOnOptions).not.toHaveBeenCalled()
  })

  it('第二件半价从可增删订单行唯一导出 SPU、金额、数量和 lines', async () => {
    vi.mocked(spuDiscount).mockResolvedValueOnce(ok(DISCOUNT_MISS))
    const wrapper = mountView()
    await chooseScenario(wrapper, 'second-half')

    expect(wrapper.find('[data-testid="v-spu"]').exists()).toBe(false)
    await wrapper.get('[data-testid="v-line-spu-0"]').setValue('990011')
    await wrapper.get('[data-testid="v-line-price-0"]').setValue('100')
    await wrapper.get('[data-testid="v-line-qty-0"]').setValue('2')
    await wrapper.get('[data-testid="v-line-add"]').trigger('click')
    await wrapper.get('[data-testid="v-line-spu-1"]').setValue('990012')
    await wrapper.get('[data-testid="v-line-price-1"]').setValue('25')
    await wrapper.get('[data-testid="v-line-qty-1"]').setValue('2')

    expect(wrapper.get('[data-testid="v-line-summary"]').text()).toContain('4 件')
    expect(wrapper.get('[data-testid="v-line-summary"]').text()).toContain('250.00')
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    await flushPromises()

    expect(vi.mocked(spuDiscount).mock.calls[0][0]).toMatchObject({
      spuIdList: [990011, 990012],
      orderAmount: 250,
      quantity: 4,
      lines: [
        { spuId: 990011, unitPrice: 100, quantity: 2 },
        { spuId: 990012, unitPrice: 25, quantity: 2 },
      ],
    })

    await wrapper.get('[data-testid="v-line-remove-1"]').trigger('click')
    expect(wrapper.findAll('[data-testid^="v-line-"]').filter((node) => /^v-line-\d+$/.test(node.attributes('data-testid') || ''))).toHaveLength(1)
    expect(wrapper.find('[data-testid="validate-result"]').exists()).toBe(false)
  })

  it('第二件半价有残缺订单行时 fail-closed，不发送请求', async () => {
    const wrapper = mountView()
    await chooseScenario(wrapper, 'second-half')
    await wrapper.get('[data-testid="v-line-spu-0"]').setValue('990011')
    await wrapper.get('[data-testid="v-line-qty-0"]').setValue('2')
    await wrapper.get('[data-testid="v-discount"]').trigger('click')

    expect(wrapper.get('[data-testid="v-error"]').text()).toContain('第 1 行单价必填')
    expect(spuDiscount).not.toHaveBeenCalled()
  })

  it('买赠通道渲染 empty 状态，切换场景会清掉旧结果', async () => {
    const giftEmpty: GiftDecisionResponse = { gifts: [], traces: ['无生效买赠活动'], mode: 'rule-engine', decisionId: 'test-gift-miss' }
    vi.mocked(queryGifts).mockResolvedValueOnce(ok(giftEmpty))
    const wrapper = mountView()
    await chooseScenario(wrapper, 'gift')
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-gifts"]').trigger('click')
    await flushPromises()

    expect(queryGifts).toHaveBeenCalledTimes(1)
    expect(vi.mocked(queryGifts).mock.calls[0][0].lines).toBeNull()
    expect(wrapper.get('[data-testid="validate-result"]').text()).toContain('没有生效赠品')
    expect(wrapper.text()).toContain('无生效买赠活动')

    await chooseScenario(wrapper, 'flat')
    expect(wrapper.find('[data-testid="validate-result"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="v-discount"]').exists()).toBe(true)
  })

  it('买赠通道展示赠品明细', async () => {
    const gifts: GiftDecisionResponse = {
      gifts: [{ activityId: 'ACT-G', version: 1, batchId: 'B1', giftName: '保温杯', giftType: 'PHYSICAL', giftNum: 1, absoluteAmount: 39.9, rightType: 'GIFT' }],
      traces: ['gift activity: ACT-G'],
      mode: 'rule-engine',
      decisionId: 'test-gift',
    }
    vi.mocked(queryGifts).mockResolvedValueOnce(ok(gifts))
    const wrapper = mountView()
    await chooseScenario(wrapper, 'gift')
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-gifts"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="validate-result"]').text()).toContain('返回 1 项赠品')
    expect(wrapper.text()).toContain('保温杯')
  })

  it('加价购按 options → 用户选择 → quote 两阶段执行，并明示不占库存', async () => {
    const options: AddOnOptionsResponse = {
      options: [{ activityId: 'ACT-A', activityName: '换购活动', version: 2, itemName: '帆布袋', addOnPrice: 9.9 }],
      traces: ['加价购选项 1 个'],
    }
    const quote: AddOnQuoteResponse = {
      ok: true, activityId: 'ACT-A', itemName: '帆布袋', addOnPrice: 9.9, reason: null,
      traces: ['加价购权威报价：ACT-A/帆布袋'],
    }
    vi.mocked(queryAddOnOptions).mockResolvedValueOnce(ok(options))
    vi.mocked(quoteAddOn).mockResolvedValueOnce(ok(quote))
    const wrapper = mountView()
    await chooseScenario(wrapper, 'addon')
    await fillSpu(wrapper)

    expect(wrapper.get('[data-testid="v-inventory-note"]').text()).toContain('不会占用换购库存')
    await wrapper.get('[data-testid="v-addon-options"]').trigger('click')
    await flushPromises()
    expect(queryAddOnOptions).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="validate-result"]').text()).toContain('返回 1 个换购选项')

    await wrapper.get('[data-testid="v-addon-option-0"]').setValue()
    await wrapper.get('[data-testid="v-addon-quote"]').trigger('click')
    await flushPromises()

    expect(quoteAddOn).toHaveBeenCalledTimes(1)
    expect(vi.mocked(quoteAddOn).mock.calls[0][1]).toBe('ACT-A')
    expect(vi.mocked(quoteAddOn).mock.calls[0][2]).toBe('帆布袋')
    expect(vi.mocked(quoteAddOn).mock.calls[0][3]).toBeInstanceOf(AbortSignal)
    expect(wrapper.get('[data-testid="v-addon-quote-result"]').text()).toContain('9.90')
    expect(wrapper.get('[data-testid="v-addon-quote-result"]').text()).toContain('未占库存')
    expect(wrapper.text()).toContain('加价购权威报价：ACT-A/帆布袋')
  })

  it('加价购没有候选时展示正常 empty 结果，而不是请求错误', async () => {
    vi.mocked(queryAddOnOptions).mockResolvedValueOnce(ok({ options: [], traces: ['无生效加价购活动'] }))
    const wrapper = mountView()
    await chooseScenario(wrapper, 'addon')
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-addon-options"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="validate-result"]').text()).toContain('没有可用换购选项')
    expect(wrapper.text()).toContain('无生效加价购活动')
    expect(wrapper.find('[data-testid="v-error"]').exists()).toBe(false)
  })

  it('加价购 quote 409 单独展示失效原因并保留选项', async () => {
    vi.mocked(queryAddOnOptions).mockResolvedValueOnce(ok({
      options: [{ activityId: 'ACT-A', activityName: '换购活动', version: 2, itemName: '帆布袋', addOnPrice: 9.9 }],
      traces: [],
    }))
    vi.mocked(quoteAddOn).mockResolvedValueOnce({
      ok: false,
      status: 409,
      json: {
        ok: false,
        activityId: 'ACT-A',
        itemName: '帆布袋',
        addOnPrice: null,
        reason: '选项已下线',
        traces: ['加价购报价拒绝：选项已失效或资格不满足'],
      },
      text: '选项已下线',
    })
    const wrapper = mountView()
    await chooseScenario(wrapper, 'addon')
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-addon-options"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="v-addon-option-0"]').setValue()
    await wrapper.get('[data-testid="v-addon-quote"]').trigger('click')
    await flushPromises()

    const conflict = wrapper.get('[data-testid="v-addon-conflict"]')
    expect(conflict.text()).toContain('报价已失效（409）')
    expect(conflict.text()).toContain('选项已下线')
    expect(wrapper.text()).toContain('加价购报价拒绝：选项已失效或资格不满足')
    expect(wrapper.find('[data-testid="v-addon-option-0"]').exists()).toBe(true)
  })

  it('一口价显示原价、减免、应付，并声明试算不扣库存', async () => {
    vi.mocked(spuDiscount).mockResolvedValueOnce(ok({
      hit: true,
      hitActivityId: 'FLASH-1',
      hitActivityName: '9.9 秒杀',
      hitAmount: 90.1,
      strategy: 'MAX',
      hitVersion: 1,
      clamped: false,
      decisionId: 'test-decision',
      items: [],
      traces: [],
      mode: 'rule-engine',
    }))
    const wrapper = mountView()
    await chooseScenario(wrapper, 'flash')
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-order-amount"]').setValue('100')
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    await flushPromises()

    const breakdown = wrapper.get('[data-testid="v-price-breakdown"]')
    expect(breakdown.text()).toContain('原价¥100.00')
    expect(breakdown.text()).toContain('减免- ¥90.10')
    expect(breakdown.text()).toContain('应付¥9.90')
    expect(wrapper.get('[data-testid="v-inventory-note"]').text()).toContain('不会扣减或占用秒杀库存')
  })

  it('场景切换会中止在途请求，旧响应不能污染新场景', async () => {
    let resolveRequest!: (value: ApiResult<DiscountDecisionResponse>) => void
    vi.mocked(spuDiscount).mockReturnValueOnce(new Promise((resolve) => { resolveRequest = resolve }))
    const wrapper = mountView()
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    const signal = vi.mocked(spuDiscount).mock.calls[0][1]!
    expect(signal.aborted).toBe(false)
    expect(wrapper.find('.loading-result').exists()).toBe(true)
    expect(wrapper.get('[data-testid="v-discount"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="v-scenario"]').attributes('disabled')).toBeUndefined()

    await chooseScenario(wrapper, 'gift')
    expect(signal.aborted).toBe(true)
    expect(wrapper.find('[data-testid="v-gifts"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="validate-result"]').exists()).toBe(false)

    resolveRequest(ok({ ...DISCOUNT_MISS, traces: ['迟到的旧结果'] }))
    await flushPromises()
    expect(wrapper.text()).not.toContain('迟到的旧结果')
    expect(wrapper.find('[data-testid="v-error"]').exists()).toBe(false)
  })

  it('网络异常进入 error 状态，AbortError 不显示为失败', async () => {
    vi.mocked(spuDiscount).mockRejectedValueOnce(new Error('网络不可达'))
    const wrapper = mountView()
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="v-error"]').text()).toContain('网络不可达')
  })
  // ---- 决策平面（P1-9：验证页此前打的是 console 走库路径，快照侧问题一个都照不出） ----

  it('默认打决策平面，切到控制台走库后才改打 marketing', async () => {
    vi.mocked(spuDiscount).mockResolvedValue(ok(DISCOUNT_MISS))
    const wrapper = mountView()
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(spuDiscount).mock.calls[0][2]).toBe('decision')

    await wrapper.get('[data-testid="v-plane-console"]').trigger('click')
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(spuDiscount).mock.calls[1][2]).toBe('console')
  })

  it('决策平面 404 显示为「不可达」而不是「未命中」——两者处置完全相反', async () => {
    vi.mocked(spuDiscount).mockResolvedValueOnce({ ok: false, status: 404, json: null, text: '' })
    const wrapper = mountView()
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="v-plane-unreachable"]').text()).toContain('决策服务不可达')
    expect(wrapper.find('[data-testid="validate-result"]').exists()).toBe(false)
  })

  it('401/403 判为「可达但未授权」，不当成不可达', async () => {
    vi.mocked(spuDiscount).mockResolvedValueOnce({ ok: false, status: 403, json: null, text: '' })
    const wrapper = mountView()
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="v-plane-unreachable"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="v-error"]').text()).toContain('授权问题')
  })

  it('回显物料来源与代际，并标出快照落后库里几代', async () => {
    vi.mocked(spuDiscount).mockResolvedValueOnce(ok({
      ...DISCOUNT_MISS,
      provenance: { source: 'snapshot' as const, generation: 5, buckets: 2 },
    }))
    vi.mocked(currentGeneration).mockResolvedValueOnce(
      ok({ bizLine: 'mall', generation: 7, note: '' }))
    vi.mocked(snapshotDiagnostics).mockResolvedValueOnce(
      ok({ tenant: 't', buckets: [], bucketCount: 0 }))
    const wrapper = mountView()
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    await flushPromises()

    const badge = wrapper.get('[data-testid="v-provenance"]')
    expect(badge.text()).toContain('代际 5')
    expect(badge.text()).toContain('2 个业务线桶')
    expect(wrapper.get('[data-testid="v-generation-behind"]').text()).toContain('落后 2 代')
  })

  it('后端没回传 provenance 时显式说「无法自证」，不默认成走库', async () => {
    vi.mocked(spuDiscount).mockResolvedValueOnce(ok(DISCOUNT_MISS))
    const wrapper = mountView()
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="v-provenance-missing"]').text()).toContain('无法自证')
  })

  it('逐活动明细把被淘汰的候选连同原因一起渲染出来', async () => {
    vi.mocked(spuDiscount).mockResolvedValueOnce(ok({
      ...DISCOUNT_MISS,
      items: [
        { activityId: 'ACT-1', activityName: '满减', version: 2, benefitForm: 'AMOUNT', amount: 20, applied: true, rejectReason: null },
        { activityId: 'ACT-2', activityName: '八折', version: 1, benefitForm: 'RATIO_ZHE', amount: 0, applied: false, rejectReason: '不满足资格条件' },
      ],
    }))
    const wrapper = mountView()
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    await flushPromises()

    const table = wrapper.get('[data-testid="v-items"]')
    expect(table.text()).toContain('不满足资格条件')
    expect(table.findAll('tr.rejected')).toHaveLength(1)
  })

  it('双打对拍：两侧都读到快照时判红——那是对拍失效，不是一致', async () => {
    const snap = { source: 'snapshot' as const, generation: 3, buckets: 1 }
    vi.mocked(spuDiscount).mockResolvedValue(ok({ ...DISCOUNT_MISS, provenance: snap }))
    const wrapper = mountView()
    await wrapper.get('[data-testid="v-plane-compare"]').setValue(true)
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    await flushPromises()

    expect(vi.mocked(spuDiscount)).toHaveBeenCalledTimes(2)
    expect(vi.mocked(spuDiscount).mock.calls[1][2]).toBe('console')
    expect(wrapper.get('[data-testid="v-diff-degraded"]').text()).toContain('恒绿')
    expect(wrapper.find('[data-testid="v-diff-match"]').exists()).toBe(false)
  })

  it('双打对拍：金额不一致时判红并逐字段列出', async () => {
    vi.mocked(spuDiscount)
      .mockResolvedValueOnce(ok({ ...DISCOUNT_MISS, hit: false, hitAmount: 0,
        provenance: { source: 'snapshot' as const, generation: 3, buckets: 1 } }))
      .mockResolvedValueOnce(ok({ ...DISCOUNT_MISS, hit: true, hitActivityId: 'ACT-9', hitAmount: 50,
        provenance: { source: 'db' as const, generation: null, buckets: 0 } }))
    const wrapper = mountView()
    await wrapper.get('[data-testid="v-plane-compare"]').setValue(true)
    await fillSpu(wrapper)
    await wrapper.get('[data-testid="v-discount"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="v-diff-mismatch"]').text()).toContain('不一致')
    expect(wrapper.get('[data-testid="v-plane-diff"]').findAll('tr.bad').length).toBeGreaterThan(0)
  })

  it('快照探针：活动不在任何桶里时给出明确结论', async () => {
    vi.mocked(snapshotDiagnostics).mockResolvedValueOnce(ok({
      tenant: 't', buckets: [{ bizLine: 'mall', generation: 4, builtAt: null, ageSeconds: 12, activityCount: 3, containsActivity: false }],
      bucketCount: 1, activityId: 'ACT-X', inSnapshot: false, hostedByBizLines: [],
      hint: '该活动不在本租户的任何快照桶里',
    }))
    const wrapper = mountView()
    await wrapper.get('[data-testid="v-snapshot-probe-input"]').setValue('ACT-X')
    await wrapper.get('[data-testid="v-snapshot-probe-run"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="v-snapshot-absent"]').text()).toContain('不在本租户的任何快照桶里')
  })
})
