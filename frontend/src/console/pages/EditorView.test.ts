import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useAuthStore } from '@/auth/useAuthStore'
import { useToast } from '@/shared/useToast'
import EditorView from './EditorView.vue'

function response(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: vi.fn().mockResolvedValue(JSON.stringify(body)),
  } as unknown as Response
}

async function setup(authEnabled = false, target = '/console/activities/new') {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.cfg = { authEnabled }

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/console/activities', name: 'activities', component: { template: '<div />' } },
      { path: '/console/activities/new', name: 'activity-new', component: EditorView },
      { path: '/console/activities/:id/edit', name: 'activity-edit', component: EditorView },
      { path: '/console/playbooks', name: 'playbooks', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })
  await router.push(target)
  await router.isReady()

  const wrapper = mount({ template: '<router-view />' }, {
    global: {
      plugins: [pinia, router],
      stubs: {
        PageHeader: { template: '<header><slot /><slot name="actions" /></header>' },
        Banner: { template: '<div><slot /></div>' },
        Section: { template: '<section><slot /></section>' },
      },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('EditorView 初始化失败降级', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    const toast = useToast()
    for (const item of [...toast.toasts.value]) toast.dismiss(item.id)
  })

  it('字段字典暂不可用时仍展示新建表单，并阻止误保存', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    const { wrapper, router } = await setup()

    expect(router.currentRoute.value.name).toBe('activity-new')
    expect(wrapper.find('[data-testid="dict-warning"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="form-name"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('无法打开活动编辑器')
    expect(wrapper.get('[data-testid="validation-errs"]').text()).toContain('字段配置未加载，暂时不能保存')
  })

  it('认证会话失效时回到登录页并保留返回地址', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(401, { message: 'unauthorized' })))

    const { router } = await setup(true)

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.returnTo).toBe('/console/activities/new')
  })
})

/**
 * 玩法模板预填（PR-6）。目录单测只能证明「常量写对了」，
 * 证明不了「跳过来之后真的填进表单了」——那是跨屏的 query → initialize → Draft 链路。
 */
describe('EditorView 玩法模板预填', () => {
  const FIELD_DICT = {
    fields: [{ key: 'orderAmount', label: '订单金额', valueType: 'NUMBER', operators: ['ge'], enumValues: [] }],
    operators: [{ code: 'ge', label: '大于等于', operand: 'SCALAR' }],
    logics: [{ code: 'AND', label: '且' }],
    activityTypes: [{ code: 1, label: '红包' }, { code: 5, label: '买赠' }, { code: 6, label: '加价购' }],
    statuses: [], distributionModes: [{ code: 1, label: '固定金额' }, { code: 2, label: '随机金额' }],
    strategies: ['MAX'],
  }

  afterEach(() => {
    vi.unstubAllGlobals()
    const toast = useToast()
    for (const item of [...toast.toasts.value]) toast.dismiss(item.id)
  })

  function dictOk() {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(200, FIELD_DICT)))
  }

  function captureCreates(statuses: number[] = [200]) {
    const bodies: Array<Record<string, any>> = []
    let createIndex = 0
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (String(url).includes('/field-dict')) return Promise.resolve(response(200, FIELD_DICT))
      if (String(url).endsWith('/create')) {
        bodies.push(JSON.parse(String(init?.body)))
        const status = statuses[Math.min(createIndex++, statuses.length - 1)]
        return Promise.resolve(response(status, status < 300
          ? { activityId: `CREATED-${createIndex}`, version: 1, autoBoundCount: 0, idempotentHit: false }
          : { message: 'save failed' }))
      }
      return Promise.resolve(response(404, { message: 'not found' }))
    }))
    return bodies
  }

  it('?playbook=ladder 把阶梯档位填进表单并说明这是模板起点', async () => {
    dictOk()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=ladder')

    const banner = wrapper.get('[data-testid="playbook-applied"]')
    expect(banner.text()).toContain('阶梯满减')
    expect(banner.text()).toContain('每一项都可以改')
    const name = wrapper.get('[data-testid="form-name"]')
    expect((name.element as HTMLInputElement).value).toBe('')
    expect(name.attributes('placeholder')).toBe('阶梯满减')
    // 阶梯模式下人话预览应已经在讲三档
    expect(wrapper.get('[data-testid="tier-plain"]').text()).toContain('300')
  })

  it('?playbook=threshold 预填固定金额，且条件树带上订单金额门槛', async () => {
    dictOk()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=threshold')

    expect((wrapper.get('[data-testid="form-amount"]').element as HTMLInputElement).value).toBe('20')
    expect(wrapper.findAll('[data-testid="cond-leaf"]').length).toBeGreaterThan(0)
  })

  it('「随机金额」是一等形态，选中后换成区间输入并说明是确定性随机', async () => {
    dictOk()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=flat')

    expect(wrapper.find('[data-testid="form-take-type"]').exists()).toBe(false)
    await wrapper.get('[data-testid="mode-random"]').trigger('click')
    expect(wrapper.get('[data-testid="mode-random"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.find('[data-testid="form-range-min"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="form-range-max"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="form-amount"]').exists()).toBe(false)
    // 必须写明它不是真抽奖——否则运营会以为同一用户多刷几次能拿到不同金额
    expect(wrapper.text()).toContain('确定性随机')
  })

  it('随机金额空区间与逆序区间都不许提交，合法区间按数字序列化', async () => {
    const bodies = captureCreates()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=flat')
    await wrapper.get('[data-testid="mode-random"]').trigger('click')
    await wrapper.get('[data-testid="form-name"]').setValue('随机金额用例')

    const errors = () => wrapper.get('[data-testid="validation-errs"]').text()
    expect(errors()).toContain('随机金额下限必填')
    expect(errors()).toContain('随机金额上限必填')
    expect(errors()).not.toContain('固定红包金额必填')
    await wrapper.get('[data-testid="submit"]').trigger('click')
    expect(bodies).toHaveLength(0)

    await wrapper.get('[data-testid="form-range-min"]').setValue('20')
    await wrapper.get('[data-testid="form-range-max"]').setValue('5')
    expect(errors()).toContain('0 ≤ 下限 ≤ 上限')

    await wrapper.get('[data-testid="form-range-min"]').setValue('5')
    await wrapper.get('[data-testid="form-range-max"]').setValue('20')
    expect(wrapper.find('[data-testid="validation-errs"]').exists()).toBe(false)
    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()

    expect(bodies).toHaveLength(1)
    expect(JSON.parse(bodies[0].redPackageRangeAmount)).toEqual({ min: 5, max: 20 })
  })

  const BENEFIT_CONTRACT_CASES = [
    { name: '固定金额', playbook: 'flat', unit: '元', takeType: 1, amount: 10, maxDiscount: null, range: 'none' },
    { name: '随机金额', playbook: 'flat', unit: '元', takeType: 2, amount: null, maxDiscount: null, range: 'random' },
    { name: '阶梯分档', playbook: 'ladder', unit: '元', takeType: null, amount: null, maxDiscount: null, range: 'ladder' },
    { name: '折扣', playbook: 'discount', unit: '折', takeType: null, amount: 8, maxDiscount: 50, range: 'none' },
    { name: '一口价', playbook: 'flash', unit: '价', takeType: null, amount: 9.9, maxDiscount: null, range: 'none' },
    { name: '第 N 件折', playbook: 'second-half', unit: '件折', takeType: null, amount: 5, maxDiscount: null, range: 'nth' },
  ] as const

  it.each(BENEFIT_CONTRACT_CASES)('$name 的提交映射保持后端判别契约', async (testCase) => {
    const bodies = captureCreates()
    const { wrapper } = await setup(false, `/console/activities/new?playbook=${testCase.playbook}`)
    await wrapper.get('[data-testid="form-name"]').setValue(`契约-${testCase.name}`)
    if (testCase.range === 'random') {
      await wrapper.get('[data-testid="mode-random"]').trigger('click')
      await wrapper.get('[data-testid="form-range-min"]').setValue('5')
      await wrapper.get('[data-testid="form-range-max"]').setValue('20')
    }

    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()
    expect(bodies).toHaveLength(1)
    const body = bodies[0]
    expect(body).toMatchObject({
      redPackageAmountUnit: testCase.unit,
      redPackageTakeType: testCase.takeType,
      redPackageAmount: testCase.amount,
      redPackageMaxDiscount: testCase.maxDiscount,
    })
    if (testCase.range === 'none') expect(body.redPackageRangeAmount).toBeNull()
    if (testCase.range === 'random') expect(JSON.parse(body.redPackageRangeAmount)).toEqual({ min: 5, max: 20 })
    if (testCase.range === 'ladder') expect(Array.isArray(JSON.parse(body.redPackageRangeAmount))).toBe(true)
    if (testCase.range === 'nth') expect(JSON.parse(body.redPackageRangeAmount)).toEqual({ nth: 2 })
  })

  const MODE_FIXTURES = [
    { mode: 'fixed', playbook: 'flat' },
    { mode: 'random', playbook: 'flat' },
    { mode: 'ladder', playbook: 'ladder' },
    { mode: 'ratio', playbook: 'discount' },
    { mode: 'price', playbook: 'flash' },
    { mode: 'nth', playbook: 'second-half' },
  ] as const
  const MODE_SWITCH_CASES = MODE_FIXTURES.flatMap((source) =>
    MODE_FIXTURES.filter((target) => target.mode !== source.mode).map((target) => ({ source, target })))

  it.each(MODE_SWITCH_CASES)('$source.mode → $target.mode 不沿用旧形态数值', async ({ source, target }) => {
    dictOk()
    const { wrapper } = await setup(false, `/console/activities/new?playbook=${source.playbook}`)
    if (source.mode === 'random') {
      await wrapper.get('[data-testid="mode-random"]').trigger('click')
      await wrapper.get('[data-testid="form-range-min"]').setValue('5')
      await wrapper.get('[data-testid="form-range-max"]').setValue('20')
    }
    await wrapper.get(`[data-testid="mode-${target.mode}"]`).trigger('click')

    if (target.mode === 'fixed') expect((wrapper.get('[data-testid="form-amount"]').element as HTMLInputElement).value).toBe('')
    if (target.mode === 'random') {
      expect((wrapper.get('[data-testid="form-range-min"]').element as HTMLInputElement).value).toBe('')
      expect((wrapper.get('[data-testid="form-range-max"]').element as HTMLInputElement).value).toBe('')
    }
    if (target.mode === 'ladder') expect(wrapper.findAll('[data-testid^="tier-reward-"]')).toHaveLength(0)
    if (target.mode === 'ratio') {
      expect((wrapper.get('[data-testid="form-zhe"]').element as HTMLInputElement).value).toBe('')
      expect((wrapper.get('[data-testid="form-max-discount"]').element as HTMLInputElement).value).toBe('')
    }
    if (target.mode === 'price') {
      expect((wrapper.get('[data-testid="form-price"]').element as HTMLInputElement).value).toBe('')
      expect((wrapper.get('[data-testid="form-seckill-inventory"]').element as HTMLInputElement).value).toBe('')
    }
    if (target.mode === 'nth') {
      expect((wrapper.get('[data-testid="form-nth"]').element as HTMLInputElement).value).toBe('')
      expect((wrapper.get('[data-testid="form-nth-zhe"]').element as HTMLInputElement).value).toBe('')
    }
  })

  it('形态切换 toast 可一次撤销并恢复被清理的原形态字段', async () => {
    dictOk()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=flash')
    await wrapper.get('[data-testid="mode-fixed"]').trigger('click')
    expect((wrapper.get('[data-testid="form-amount"]').element as HTMLInputElement).value).toBe('')

    const toast = useToast()
    const undo = toast.toasts.value.at(-1)?.actions?.find((action) => action.testid === 'undo-red-mode')
    expect(undo).toBeTruthy()
    undo!.onClick()
    await flushPromises()

    expect(wrapper.get('[data-testid="mode-price"]').attributes('aria-pressed')).toBe('true')
    expect((wrapper.get('[data-testid="form-price"]').element as HTMLInputElement).value).toBe('9.9')
    expect((wrapper.get('[data-testid="form-seckill-inventory"]').element as HTMLInputElement).value).toBe('100')
  })

  it('一口价库存为空或小于 1 时前端阻止保存', async () => {
    dictOk()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=flash')
    const inventory = wrapper.get('[data-testid="form-seckill-inventory"]')

    await inventory.setValue('')
    expect(wrapper.get('[data-testid="validation-errs"]').text()).toContain('秒杀库存必须')
    await inventory.setValue('0')
    expect(wrapper.get('[data-testid="validation-errs"]').text()).toContain('秒杀库存必须')
    await inventory.setValue('1.5')
    expect(wrapper.get('[data-testid="validation-errs"]').text()).toContain('整数')
  })

  it('买赠没有赠品时不再显示假绿灯', async () => {
    dictOk()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=gift')
    expect(wrapper.get('[data-testid="validation-errs"]').text()).toContain('买赠活动至少需配置一个赠品')
    expect(wrapper.text()).not.toContain('配置检查通过')
  })

  it('保存成功后的首次编辑重铸 requestId，随后可创建新的逻辑请求', async () => {
    const bodies = captureCreates()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=flat')
    await wrapper.get('[data-testid="form-name"]').setValue('首次保存')

    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="save-success"]').exists()).toBe(true)

    await wrapper.get('[data-testid="form-amount"]').setValue('11')
    expect(wrapper.find('[data-testid="save-success"]').exists()).toBe(false)
    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()

    expect(bodies).toHaveLength(2)
    expect(bodies[1].requestId).not.toBe(bodies[0].requestId)
  })

  it('保存失败后直接重试沿用 requestId，保留幂等重试语义', async () => {
    const bodies = captureCreates([500, 200])
    const { wrapper } = await setup(false, '/console/activities/new?playbook=flat')
    await wrapper.get('[data-testid="form-name"]').setValue('失败重试')

    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()

    expect(bodies).toHaveLength(2)
    expect(bodies[1].requestId).toBe(bodies[0].requestId)
  })

  it('同 record 从 ?playbook=flash 导航到无 query 会重置草稿并重铸 requestId', async () => {
    const bodies = captureCreates()
    const { wrapper, router } = await setup(false, '/console/activities/new?playbook=flash')
    await wrapper.get('[data-testid="form-name"]').setValue('第一张秒杀')
    await wrapper.get('[data-testid="spu-row-input"]').setValue('900001')
    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()
    expect(bodies).toHaveLength(1)

    await router.push('/console/activities/new')
    await flushPromises()
    expect(wrapper.find('[data-testid="form-price"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="mode-fixed"]').attributes('aria-pressed')).toBe('true')
    expect((wrapper.get('[data-testid="form-name"]').element as HTMLInputElement).value).toBe('')
    expect(wrapper.find('[data-testid="playbook-applied"]').exists()).toBe(false)

    await wrapper.get('[data-testid="form-name"]').setValue('第二张立减')
    await wrapper.get('[data-testid="form-amount"]').setValue('10')
    await wrapper.get('[data-testid="spu-row-input"]').setValue('900002')
    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()
    expect(bodies).toHaveLength(2)
    expect(bodies[1].requestId).not.toBe(bodies[0].requestId)
  })

  it('模板身份只作起点提示：普通编辑标记已改动，切类型或形态后失效', async () => {
    dictOk()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=flat')
    expect(wrapper.get('[data-testid="playbook-applied"]').text()).not.toContain('已改动')

    await wrapper.get('[data-testid="form-amount"]').setValue('12')
    expect(wrapper.get('[data-testid="playbook-applied"]').text()).toContain('已改动')

    await wrapper.get('[data-testid="mode-ratio"]').trigger('click')
    expect(wrapper.find('[data-testid="playbook-applied"]').exists()).toBe(false)
  })

  it('不带 playbook 参数时不显示模板提示，行为与改造前一致', async () => {
    dictOk()
    const { wrapper } = await setup()
    expect(wrapper.find('[data-testid="playbook-applied"]').exists()).toBe(false)
  })

  it('?playbook=discount 预填折数与封顶，人话预览讲清「最多减多少、什么时候到顶」', async () => {
    dictOk()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=discount')

    expect((wrapper.get('[data-testid="form-zhe"]').element as HTMLInputElement).value).toBe('8')
    expect((wrapper.get('[data-testid="form-max-discount"]').element as HTMLInputElement).value).toBe('50')
    const plain = wrapper.get('[data-testid="ratio-plain"]').text()
    expect(plain).toContain('8 折')
    expect(plain).toContain('最多减 50 元')
    // 8 折 = 减免 20%，封顶 50 → 订单满 250 元起就到顶
    expect(plain).toContain('250')
  })

  it('折扣型没填封顶不许保存——不封顶等于无上限支出', async () => {
    dictOk()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=discount')

    await wrapper.get('[data-testid="form-max-discount"]').setValue('')
    expect(wrapper.get('[data-testid="validation-errs"]').text()).toContain('封顶')
  })

  it('折数越界（10 折 = 不打折）不许保存', async () => {
    dictOk()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=discount')

    await wrapper.get('[data-testid="form-zhe"]').setValue('10')
    expect(wrapper.get('[data-testid="validation-errs"]').text()).toContain('折数')
  })

  it('未知 playbook id 被忽略，不炸也不预填', async () => {
    dictOk()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=不存在的玩法')
    expect(wrapper.find('[data-testid="playbook-applied"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="form-name"]').exists()).toBe(true)
  })

  /**
   * 加价购（PR：写平面放行 type=6 + 编辑器加换购品 + 必填校验，三件一起）。
   *
   * <p>从前这个模板会把 `activityType=6` 填进草稿，然后：类型 chip 全不高亮（白名单只有 1/5）、
   * 第 2 步整段不渲染、`gifts` 恒 null、`formValid` 对 type=6 零规则所以保存键永远可点——
   * 运营填完整张表，在保存时收一个「demo 仅支持红包(1) / 买赠(5)」。这几条守的就是那条链路。
   */
  describe('EditorView 加价购', () => {
    it('?playbook=addon 落到加价购类型，并渲染换购品配置区', async () => {
      dictOk()
      const { wrapper } = await setup(false, '/console/activities/new?playbook=addon')

      // 类型 chip 必须真的选中——白名单只有 1/5 的年代，它是全不高亮的
      expect(wrapper.get('[data-testid="type-chip-6"]').classes()).toContain('active')
      // 第 2 步渲染的是换购品配置区（空表只有「添加」按钮，行是后面点出来的）
      expect(wrapper.find('[data-testid="addon-item-add"]').exists()).toBe(true)
      expect(wrapper.text()).toContain('第二阶段报价的匹配依据')
      // 红包那套字段不该出现在加价购表单里
      expect(wrapper.find('[data-testid="form-amount"]').exists()).toBe(false)

      await wrapper.get('[data-testid="addon-item-add"]').trigger('click')
      expect(wrapper.find('[data-testid="addon-name-input"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="addon-price-input"]').exists()).toBe(true)
    })

    it('换购品为空 / 无名 / 加价额 ≤0 / 重名都拦在保存之前', async () => {
      dictOk()
      const { wrapper } = await setup(false, '/console/activities/new?playbook=addon')
      // 校验全过时这个盒子整块不渲染，所以不能用 get()——那会把「没有错误」变成测试崩溃
      const errs = () => wrapper.find('[data-testid="validation-errs"]').exists()
        ? wrapper.get('[data-testid="validation-errs"]').text() : ''

      await wrapper.get('[data-testid="form-name"]').setValue('加价购用例')
      expect(errs()).toContain('至少需配置一个换购品')

      // 加一行：默认无名、加价额为空
      await wrapper.get('[data-testid="addon-item-add"]').trigger('click')
      expect(errs()).toContain('换购品名称必填')
      expect(errs()).toContain('加价金额必须大于 0')

      const names = wrapper.findAll('[data-testid="addon-name-input"]')
      const prices = wrapper.findAll('[data-testid="addon-price-input"]')
      await names[0].setValue('保温杯')
      await prices[0].setValue('9.9')
      expect(errs()).not.toContain('换购品')

      // 再加一行同名 → 第二阶段按品名匹配，重名会选不中
      await wrapper.get('[data-testid="addon-item-add"]').trigger('click')
      const names2 = wrapper.findAll('[data-testid="addon-name-input"]')
      const prices2 = wrapper.findAll('[data-testid="addon-price-input"]')
      await names2[1].setValue('保温杯')
      await prices2[1].setValue('19.9')
      expect(errs()).toContain('不能重复')
    })

    it('买赠 ↔ 加价购 互切时清空明细行——同一列两种含义，带过去等于静默改语义', async () => {
      dictOk()
      const { wrapper } = await setup(false, '/console/activities/new?playbook=addon')

      await wrapper.get('[data-testid="addon-item-add"]').trigger('click')
      await wrapper.findAll('[data-testid="addon-name-input"]')[0].setValue('保温杯')
      await wrapper.findAll('[data-testid="addon-price-input"]')[0].setValue('9.9')

      // 切到买赠：9.9 的「加价额」若原样留下，会变成 9.9 的「赠品价值」，而写平面照收不误
      await wrapper.get('[data-testid="type-chip-5"]').trigger('click')
      expect(wrapper.findAll('[data-testid="gift-row"]').length).toBe(0)
    })
  })

  /**
   * 编辑回读的**形态保真**。
   *
   * <p>这条链路此前一行测试都没有（测试路由表里根本没有 activity-edit），
   * 于是「回读认不出一口价」这件事一直没人发现：'价' 落进兜底分支被读成「固定金额 9.9」，
   * 表单全绿，再保存时 submit 从 redMode 反推出 '元' —— 一个「9.9 元卖」的秒杀
   * 被静默改写成「减 9.9 元」的立减券。**回读丢形态 = 编辑一次就改钱**，所以这几条守的是钱不是体验。
   */
  describe('EditorView 编辑回读（形态保真）', () => {
    function detailOf(rule: Record<string, unknown>) {
      return {
        manage: {
          activityType: 1, activityName: '回读用例', bizLine: 'mall', activityRule: '',
          priority: 1, inventory: 100, activityAreaType: 1, districtIds: '',
          activityStartTime: '2026-08-01T10:00:00Z', activityEndTime: '2026-08-08T10:00:00Z',
        },
        rules: [rule], conditions: [], gifts: [], bindings: [], poolRefs: [],
      }
    }

    function backendReturns(rule: Record<string, unknown>) {
      vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) =>
        Promise.resolve(response(200, String(url).includes('/field-dict') ? FIELD_DICT : detailOf(rule)))))
    }

    const ROUNDTRIP_CASES = [
      { name: '固定金额', rule: { redPackageTakeType: 1, redPackageAmountUnit: '元', redPackageAmount: 10, redPackageMaxDiscount: null, redPackageRangeAmount: null } },
      { name: '随机金额', rule: { redPackageTakeType: 2, redPackageAmountUnit: '元', redPackageAmount: null, redPackageMaxDiscount: null, redPackageRangeAmount: '{"min":5,"max":20}' } },
      { name: '阶梯分档', rule: { redPackageTakeType: null, redPackageAmountUnit: '元', redPackageAmount: null, redPackageMaxDiscount: null, redPackageRangeAmount: '[{"min":0,"max":100,"reward":5}]' } },
      { name: '折扣', rule: { redPackageTakeType: null, redPackageAmountUnit: '折', redPackageAmount: 8, redPackageMaxDiscount: 50, redPackageRangeAmount: null } },
      { name: '一口价', rule: { redPackageTakeType: null, redPackageAmountUnit: '价', redPackageAmount: 9.9, redPackageMaxDiscount: null, redPackageRangeAmount: null } },
      { name: '第 N 件折', rule: { redPackageTakeType: null, redPackageAmountUnit: '件折', redPackageAmount: 5, redPackageMaxDiscount: null, redPackageRangeAmount: '{"nth":3}' } },
    ] as const

    it.each(ROUNDTRIP_CASES)('$name 回读后原样保存，权益判别字段保持互逆', async ({ rule }) => {
      const bodies: Array<Record<string, any>> = []
      vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: RequestInit) => {
        if (String(url).includes('/field-dict')) return Promise.resolve(response(200, FIELD_DICT))
        if (String(url).endsWith('/create')) {
          bodies.push(JSON.parse(String(init?.body)))
          return Promise.resolve(response(200, { activityId: 'ROUNDTRIP', version: 2, autoBoundCount: 0, idempotentHit: false }))
        }
        return Promise.resolve(response(200, detailOf(rule)))
      }))

      const { wrapper } = await setup(false, '/console/activities/ROUNDTRIP/edit')
      await wrapper.get('[data-testid="submit"]').trigger('click')
      await flushPromises()

      expect(bodies).toHaveLength(1)
      const body = bodies[0]
      expect(body).toMatchObject({
        redPackageTakeType: rule.redPackageTakeType,
        redPackageAmountUnit: rule.redPackageAmountUnit,
        redPackageAmount: rule.redPackageAmount,
        redPackageMaxDiscount: rule.redPackageMaxDiscount,
      })
      if (rule.redPackageRangeAmount == null) expect(body.redPackageRangeAmount).toBeNull()
      else expect(JSON.parse(body.redPackageRangeAmount)).toEqual(JSON.parse(rule.redPackageRangeAmount))
    })

    it('一口价（单位=价）回读成一口价，绝不降级成固定金额红包', async () => {
      backendReturns({ redPackageTakeType: 1, redPackageAmountUnit: '价', redPackageAmount: 9.9, redPackageRangeAmount: null })
      const { wrapper } = await setup(false, '/console/activities/A1/edit')

      expect(wrapper.get('[data-testid="mode-price"]').attributes('aria-pressed')).toBe('true')
      expect((wrapper.get('[data-testid="form-price"]').element as HTMLInputElement).value).toBe('9.9')
      // 关键否定断言：一旦这里又出现「红包金额」输入框，说明形态被读丢了，保存就会改钱
      expect(wrapper.find('[data-testid="form-amount"]').exists()).toBe(false)
    })

    it('第 N 件折（单位=件折）连 N 一起回读，N 来自 {"nth":N} 而不是表单默认值', async () => {
      backendReturns({ redPackageTakeType: 1, redPackageAmountUnit: '件折', redPackageAmount: 5, redPackageRangeAmount: '{"nth":3}' })
      const { wrapper } = await setup(false, '/console/activities/A2/edit')

      expect(wrapper.get('[data-testid="mode-nth"]').attributes('aria-pressed')).toBe('true')
      // 默认值是 2；读回 3 才能证明 N 真的是从活动里来的
      expect((wrapper.get('[data-testid="form-nth"]').element as HTMLInputElement).value).toBe('3')
      expect((wrapper.get('[data-testid="form-nth-zhe"]').element as HTMLInputElement).value).toBe('5')
      // {"nth":3} 是对象，绝不能被当成阶梯分档
      expect(wrapper.find('[data-testid="tier-plain"]').exists()).toBe(false)
    })

    it('折扣型与阶梯型回读不受影响（旧行为零变更）', async () => {
      backendReturns({ redPackageTakeType: 1, redPackageAmountUnit: '折', redPackageAmount: 8, redPackageMaxDiscount: 50, redPackageRangeAmount: null })
      const { wrapper } = await setup(false, '/console/activities/A3/edit')
      expect((wrapper.get('[data-testid="form-zhe"]').element as HTMLInputElement).value).toBe('8')
      expect((wrapper.get('[data-testid="form-max-discount"]').element as HTMLInputElement).value).toBe('50')

      backendReturns({ redPackageTakeType: 1, redPackageAmountUnit: '元', redPackageAmount: null, redPackageRangeAmount: '[{"min":300,"max":600,"reward":50}]' })
      const second = await setup(false, '/console/activities/A4/edit')
      expect(second.wrapper.get('[data-testid="tier-plain"]').text()).toContain('300')
    })

    it('脏随机区间仍回读为 random 并被必填校验拦住，不降级 fixed', async () => {
      backendReturns({ redPackageTakeType: 2, redPackageAmountUnit: '元', redPackageAmount: null, redPackageRangeAmount: '{"min":5,"max":1}' })
      const { wrapper } = await setup(false, '/console/activities/A5/edit')

      expect(wrapper.get('[data-testid="mode-random"]').attributes('aria-pressed')).toBe('true')
      expect(wrapper.find('[data-testid="form-amount"]').exists()).toBe(false)
      expect(wrapper.get('[data-testid="validation-errs"]').text()).toContain('随机金额下限必填')
    })

    it('脏非空阶梯仍回读为 ladder 并要求重填，防止下一次保存静默抹 range', async () => {
      backendReturns({ redPackageTakeType: 1, redPackageAmountUnit: '元', redPackageAmount: null, redPackageRangeAmount: '[{"min":0}]' })
      const { wrapper } = await setup(false, '/console/activities/A6/edit')

      expect(wrapper.get('[data-testid="mode-ladder"]').attributes('aria-pressed')).toBe('true')
      expect(wrapper.get('[data-testid="validation-errs"]').text()).toContain('阶梯档至少一档有奖励')
    })

    it('六种形态都能用 chip 切到——不存在只进不出的单向门', async () => {
      dictOk()
      const { wrapper } = await setup(false, '/console/activities/new?playbook=flash')

      // 从模板进来是一口价；点走再点回来，必须还能回到一口价
      expect(wrapper.get('[data-testid="mode-price"]').attributes('aria-pressed')).toBe('true')
      await wrapper.get('[data-testid="mode-ratio"]').trigger('click')
      expect(wrapper.find('[data-testid="form-price"]').exists()).toBe(false)
      await wrapper.get('[data-testid="mode-price"]').trigger('click')
      expect(wrapper.find('[data-testid="form-price"]').exists()).toBe(true)
    })
  })
})
