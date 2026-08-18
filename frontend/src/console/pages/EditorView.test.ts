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

  /**
   * 行政区字典夹具。**故意只有 5 行**：3212 行真实数据 × 每个 it 一次完整 mount，
   * 在 jsdom 里是白烧时间；「大数据集不崩」单独由 districtLogic 那条用例守。
   */
  const DISTRICTS = [
    { code: '440000', name: '广东省', shortName: '广东', level: 1, parent: null, pinyin: 'guangdong', pinyinInitial: 'g' },
    { code: '440300', name: '深圳市', shortName: '深圳', level: 2, parent: '440000', pinyin: 'shenzhen', pinyinInitial: 's' },
    { code: '440305', name: '南山区', shortName: '南山', level: 3, parent: '440300', pinyin: 'nanshan', pinyinInitial: 'n' },
    { code: '110000', name: '北京市', shortName: '北京', level: 1, parent: null, pinyin: 'beijing', pinyinInitial: 'b' },
    { code: '110101', name: '东城区', shortName: '东城', level: 3, parent: '110000', pinyin: 'dongcheng', pinyinInitial: 'd' },
  ]

  function dictOk() {
    // 三个 helper 都必须认 /districts：它们都是「按 URL 分派 + 其余兜底」，
    // 而兜底各不相同（这里恒 FieldDict、captureCreates 是 404、backendReturns 是详情 JSON）。
    // 漏一处，地域选择器就会收到一个形状完全不对的响应，而且多半是静默的。
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) =>
      Promise.resolve(String(url).includes('/districts')
        ? response(200, DISTRICTS)
        : response(200, FIELD_DICT))))
  }

  function captureCreates(statuses: number[] = [200]) {
    const bodies: Array<Record<string, any>> = []
    let createIndex = 0
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      if (String(url).includes('/field-dict')) return Promise.resolve(response(200, FIELD_DICT))
      if (String(url).includes('/districts')) return Promise.resolve(response(200, DISTRICTS))
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
   * 运营填完整张表，在保存时收一个「capability 仅支持红包(1) / 买赠(5)」。这几条守的就是那条链路。
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
      vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
        if (String(url).includes('/field-dict')) return Promise.resolve(response(200, FIELD_DICT))
        if (String(url).includes('/districts')) return Promise.resolve(response(200, DISTRICTS))
        return Promise.resolve(response(200, detailOf(rule)))
      }))
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

  /**
   * 投放地域。守两类东西：**别打扰默认路径**（「全国」是默认值，也是六条 e2e 走的路），
   * 以及**别在回读时吃掉运营存过的码**。
   */
  describe('EditorView 投放地域', () => {
    afterEach(() => { vi.unstubAllGlobals() })

    it('「全国」默认态下根本不请求字典——不给六条经过编辑页的 e2e 凭空加网络依赖', async () => {
      dictOk()
      await setup(false, '/console/activities/new?playbook=flat')
      const urls = (globalThis.fetch as any).mock.calls.map((c: any[]) => String(c[0]))
      expect(urls.some((u: string) => u.includes('/districts'))).toBe(false)
    })

    it('切到「指定地域」才拉字典，选中的码按 CSV 提交', async () => {
      const bodies = captureCreates()
      const { wrapper } = await setup(false, '/console/activities/new?playbook=flat')
      await wrapper.get('[data-testid="form-name"]').setValue('地域活动')

      await wrapper.get('[data-testid="form-area-type"]').setValue('2')
      await flushPromises()
      const urls = (globalThis.fetch as any).mock.calls.map((c: any[]) => String(c[0]))
      expect(urls.some((u: string) => u.includes('/districts'))).toBe(true)

      await wrapper.get('[data-testid="district-toggle"]').trigger('click')
      await wrapper.get('[data-testid="district-opt-440000"]').setValue(true)
      await flushPromises()

      await wrapper.get('[data-testid="submit"]').trigger('click')
      await flushPromises()
      expect(bodies[0].districtIds).toBe('440000')
      expect(bodies[0].activityAreaType).toBe(2)
    })

    it('选了省，再点它下面的市不会重复占名额（后端展开时本来就包含）', async () => {
      dictOk()
      const { wrapper } = await setup(false, '/console/activities/new?playbook=flat')
      await wrapper.get('[data-testid="form-area-type"]').setValue('2')
      await flushPromises()
      await wrapper.get('[data-testid="district-toggle"]').trigger('click')

      await wrapper.get('[data-testid="district-opt-440000"]').setValue(true)
      await flushPromises()
      // 广东被选中后，展开它，深圳这一项应当是禁用的（它已经被包含了）
      await wrapper.get('[data-testid="district-expand-440000"]').trigger('click')
      await flushPromises()
      expect(wrapper.get('[data-testid="district-opt-440300"]').attributes('disabled')).toBeDefined()
      expect(wrapper.get('[data-testid="district-count"]').text()).toContain('已选 1')
    })

    it('「指定地域」但一个都没选 → 拦在保存前（否则详情页会回显成「指定地域」，看着像配好了）', async () => {
      dictOk()
      const { wrapper } = await setup(false, '/console/activities/new?playbook=flat')
      await wrapper.get('[data-testid="form-name"]').setValue('空投放')
      await wrapper.get('[data-testid="form-area-type"]').setValue('2')
      await flushPromises()

      expect(wrapper.get('[data-testid="validation-errs"]').text()).toContain('至少选择一个行政区')
    })

    it('回读保真：含已撤销代码的存量活动，不做任何修改直接保存，districtIds 一字不差', async () => {
      // 500105 江北区 2025-11 撤销、民政部废止代码 —— 字典里查不到，但库里存量活动可能有。
      // 选择器若按字典过滤，运营打开编辑器保存一次，这个码就永久没了，而且全链路不报错。
      const bodies: Array<Record<string, any>> = []
      vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: RequestInit) => {
        if (String(url).includes('/field-dict')) return Promise.resolve(response(200, FIELD_DICT))
        if (String(url).includes('/districts')) return Promise.resolve(response(200, DISTRICTS))
        if (String(url).endsWith('/create')) {
          bodies.push(JSON.parse(String(init?.body)))
          return Promise.resolve(response(200, { activityId: 'KEEP', version: 2, autoBoundCount: 0, idempotentHit: false }))
        }
        return Promise.resolve(response(200, {
          manage: {
            activityType: 1, activityName: '存量地域活动', bizLine: 'mall', activityRule: '',
            priority: 1, inventory: 100, activityAreaType: 2, districtIds: '500105,440305',
            activityStartTime: '2026-08-01T10:00:00Z', activityEndTime: '2026-08-08T10:00:00Z',
          },
          rules: [{ redPackageTakeType: 1, redPackageAmountUnit: '元', redPackageAmount: 10, redPackageMaxDiscount: null, redPackageRangeAmount: null }],
          conditions: [], gifts: [], bindings: [], poolRefs: [],
        }))
      }))

      const { wrapper } = await setup(false, '/console/activities/KEEP/edit')
      await flushPromises()

      // 未知码必须在界面上看得见，并且被标出来
      expect(wrapper.get('[data-testid="district-chips"]').text()).toContain('500105')
      expect(wrapper.get('[data-testid="district-unknown"]').text()).toContain('可能已撤销')

      await wrapper.get('[data-testid="submit"]').trigger('click')
      await flushPromises()
      expect(bodies[0].districtIds).toBe('500105,440305')
    })

    /**
     * `.form` 上挂的是 `@input="markDirty" @click="onFormClick"`（EditorView.vue:623），
     * **两个事件都会从选择器内部冒泡上去**。DistrictPicker 只截了 click 的话，
     * 在搜索框里打一个字就算「改过表单」：清掉刚保存的成功卡、重铸幂等 requestId、
     * 离开时还弹未保存确认——而运营其实只是想找一下「南山」在哪。
     */
    it('在地域搜索框里打字不算改表单：保存成功卡不该被清掉', async () => {
      dictOk()
      const { wrapper } = await setup(false, '/console/activities/new?playbook=flat')
      await wrapper.get('[data-testid="form-name"]').setValue('搜索不脏')
      await wrapper.get('[data-testid="form-area-type"]').setValue('2')
      await flushPromises()
      await wrapper.get('[data-testid="district-toggle"]').trigger('click')
      await wrapper.get('[data-testid="district-opt-440000"]').setValue(true)
      await flushPromises()

      await wrapper.get('[data-testid="submit"]').trigger('click')
      await flushPromises()
      expect(wrapper.find('[data-testid="save-success"]').exists()).toBe(true)

      // 只是搜索，不是改动
      await wrapper.get('[data-testid="district-search"]').setValue('南山')
      await flushPromises()
      expect(wrapper.find('[data-testid="save-success"]').exists()).toBe(true)

      // 真改了才算脏：移除 chip 走的是 v-model setter，EditorView 的 districtCodes 里显式 markDirty。
      // （用已选清单里的 chip 移除，不受搜索态影响；搜索态下树内过滤后命中仍是 district-opt-* 节点。）
      await wrapper.get('[data-testid="district-chip-x-440000"]').trigger('click')
      await flushPromises()
      expect(wrapper.find('[data-testid="save-success"]').exists()).toBe(false)
    })

    /**
     * 字典不可用时的裸 CSV 逃生门。`v-model` 每敲一个字符都走一遍 set→get，
     * 若 get 直接返回规范化结果，敲到 `440300,` 时尾随逗号会被 parseCodes 丢掉再写回，
     * **逗号刚打出来就没了，第二个码永远输不进去**——逃生门实际只能填一个地域。
     */
    it('字典不可用时的裸 CSV 逃生门能输入多个码（逗号不会被边打边吞）', async () => {
      vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) =>
        Promise.resolve(String(url).includes('/districts')
          ? response(500, { message: 'dict down' })
          : response(200, FIELD_DICT))))

      const { wrapper } = await setup(false, '/console/activities/new?playbook=flat')
      await wrapper.get('[data-testid="form-area-type"]').setValue('2')
      await flushPromises()

      const raw = wrapper.get('[data-testid="district-raw"]')
      await raw.setValue('440300,')          // 用户刚打完逗号，准备输第二个码
      expect((raw.element as HTMLInputElement).value).toBe('440300,')
      await raw.setValue('440300,110000')
      await flushPromises()
      expect(wrapper.get('[data-testid="district-count"]').text()).toContain('已选 2')

      await raw.trigger('blur')              // 失焦回到规范形
      expect((raw.element as HTMLInputElement).value).toBe('440300,110000')
    })

    /**
     * 存储树是**合成后**的那棵：写平面保存时会往里注入一片
     * `userDistrictId IN (自身+全部后代)` 并标 `source:"district"`。
     *
     * 编辑器回读的是整份存储树，所以这条注入节点必须在**进 UI 之前**被剥掉。不剥的两个后果，
     * 第二个才是致命的：
     * ① 运营在条件树里看到一条自己没写过、含上百个代码的规则，还能手动改它；
     * ② `pruneTree` 会把 `source` 一起剥掉（它原本只剥 UI 临时 id），于是这条节点
     *    以「运营手写的条件」身份提交回后端 —— 后端的幂等剥离认不出它，只好保留，
     *    再叠一条新的。把投放地域从广东改成北京，就会得到
     *    `IN(广东…) AND IN(北京…)`：**恒不命中、活动静默停发**，而全链路一声不响。
     */
    it('回读时剥掉写平面注入的地域条件：UI 里看不到，提交回去也不会被当成用户条件再叠一层', async () => {
      const bodies: Array<Record<string, any>> = []
      // ⚠ 这里必须用**后端真实写出来的形状**，不能手写成"干净"的 JSON。
      // 后端存这份用的是零配置 new ObjectMapper()（JsonInclude.ALWAYS），所以叶子上带着
      // "logic":null / "children":null，组上带着 "field":null。用干净 JSON 写这条用例，
      // 它照样绿，而线上一开编辑器条件树就整棵消失——那正是 isGroup 判别写错时的表现。
      const storedTree = {
        logic: 'AND',
        children: [
          { logic: null, children: null, field: 'userLevel', op: '>=', value: '3', source: null },
          {
            logic: null, children: null, field: 'userDistrictId', op: 'IN',
            value: ['440000', '440300', '440305'], source: 'district',
          },
        ],
        field: null, op: null, value: null, source: null,
      }
      vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: RequestInit) => {
        if (String(url).includes('/field-dict')) return Promise.resolve(response(200, FIELD_DICT))
        if (String(url).includes('/districts')) return Promise.resolve(response(200, DISTRICTS))
        if (String(url).endsWith('/create')) {
          bodies.push(JSON.parse(String(init?.body)))
          return Promise.resolve(response(200, { activityId: 'DIS', version: 2, autoBoundCount: 0, idempotentHit: false }))
        }
        return Promise.resolve(response(200, {
          manage: {
            activityType: 1, activityName: '广东专享', bizLine: 'mall', activityRule: '',
            priority: 1, inventory: 100, activityAreaType: 2, districtIds: '440000',
            activityStartTime: '2026-08-01T10:00:00Z', activityEndTime: '2026-08-08T10:00:00Z',
          },
          rules: [{ redPackageTakeType: 1, redPackageAmountUnit: '元', redPackageAmount: 10, redPackageMaxDiscount: null, redPackageRangeAmount: null }],
          conditions: [{ conditionTreeJson: JSON.stringify(storedTree) }], gifts: [], bindings: [], poolRefs: [],
        }))
      }))

      const { wrapper } = await setup(false, '/console/activities/DIS/edit')
      await flushPromises()

      // 条件树 UI 里只剩运营自己那一条
      expect(wrapper.html()).not.toContain('userDistrictId')
      // 而地域本身仍由 DistrictPicker 完整回显（同一件事只有一个控件）
      expect(wrapper.get('[data-testid="district-chips"]').text()).toContain('广东')

      await wrapper.get('[data-testid="submit"]').trigger('click')
      await flushPromises()

      const tree = bodies[0].eligibilityConditionTree
      expect(JSON.stringify(tree)).not.toContain('userDistrictId')
      expect(tree.children).toHaveLength(1)
      expect(tree.children[0].field).toBe('userLevel')
      // 地域仍旧原样带回去，由后端重新翻译一次
      expect(bodies[0].districtIds).toBe('440000')
    })
  })
})

describe('EditorView 商品绑定 · 选店铺→勾商品 picker', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  const FIELD_DICT = {
    fields: [{ key: 'orderAmount', label: '订单金额', valueType: 'NUMBER', operators: ['ge'], enumValues: [] }],
    operators: [{ key: 'ge', label: '≥' }], logics: [{ key: 'AND', label: '且' }],
    activityTypes: [{ code: 1, label: '红包' }], statuses: [{ code: 1, label: '已上线' }],
    distributionModes: [], strategies: ['MAX'],
  }

  function stubPicker(opts: {
    stores?: Array<Record<string, unknown>>
    productsByStore?: Record<number, Record<string, unknown>>
    detail?: Record<string, unknown>
  }) {
    const bodies: Array<Record<string, any>> = []
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: RequestInit) => {
      const u = String(url)
      if (u.includes('/field-dict')) return Promise.resolve(response(200, FIELD_DICT))
      if (u.includes('/districts')) return Promise.resolve(response(200, []))
      const m = u.match(/\/store-picker\/stores\/(\d+)\/products/)
      if (m) {
        const sid = Number(m[1])
        return Promise.resolve(response(200, (opts.productsByStore ?? {})[sid] ?? { total: 0, page: 0, size: 10, items: [] }))
      }
      if (u.includes('/store-picker/stores')) return Promise.resolve(response(200, opts.stores ?? []))
      if (u.endsWith('/create')) {
        bodies.push(JSON.parse(String(init?.body)))
        return Promise.resolve(response(200, { activityId: 'PK', version: 1, autoBoundCount: 0, idempotentHit: false }))
      }
      if (opts.detail) return Promise.resolve(response(200, opts.detail))
      return Promise.resolve(response(404, { message: 'not found' }))
    }))
    return bodies
  }

  it('选店铺→勾商品→提交 spuBindings 含 {storeId,spuId}（多店多商品）', async () => {
    const bodies = stubPicker({
      stores: [{ storeId: 1, storeName: '旗舰店', productCount: 1 }, { storeId: 2, storeName: '折扣店', productCount: 1 }],
      productsByStore: {
        1: { total: 1, page: 0, size: 10, items: [{ spuId: 9101, spuName: '蓝牙耳机', price: 120, onShelf: 1 }] },
        2: { total: 1, page: 0, size: 10, items: [{ spuId: 9201, spuName: '跑步鞋', price: 260, onShelf: 1 }] },
      },
    })
    const { wrapper } = await setup(false, '/console/activities/new?playbook=flat')
    await wrapper.get('[data-testid="form-name"]').setValue('picker 用例')

    await wrapper.get('[data-testid="store-picker-toggle"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="store-picker-store-1"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="store-picker-product-9101"] input[type="checkbox"]').setValue(true)
    await wrapper.get('[data-testid="store-picker-store-2"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="store-picker-product-9201"] input[type="checkbox"]').setValue(true)
    await wrapper.get('[data-testid="store-picker-confirm"]').trigger('click')

    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()

    expect(bodies).toHaveLength(1)
    expect(bodies[0].spuBindings).toEqual(
      expect.arrayContaining([{ storeId: 1, spuId: 9101 }, { storeId: 2, spuId: 9201 }]))
  })

  it('编辑回填目录外 SPU：落手填行可见、直接保存一字不差（头号金标）', async () => {
    const bodies = stubPicker({
      stores: [{ storeId: 1, storeName: '旗舰店', productCount: 1 }],
      detail: {
        manage: {
          activityType: 1, activityName: '回填用例', bizLine: 'mall', activityRule: '',
          priority: 1, inventory: 100, activityAreaType: 1, districtIds: '',
          activityStartTime: '2026-08-01T10:00:00Z', activityEndTime: '2026-08-08T10:00:00Z',
        },
        rules: [{ redPackageTakeType: 1, redPackageAmountUnit: '元', redPackageAmount: 10, redPackageRangeAmount: null }],
        conditions: [], gifts: [], poolRefs: [],
        // 990011/888888 都是目录外 SPU（不在 capability_product）——picker 里选不到，绝不能静默丢
        bindings: [{ bindSource: 0, storeId: 77, spuId: 888888 }],
      },
    })
    const { wrapper } = await setup(false, '/console/activities/OFFCAT/edit')
    await flushPromises()

    // 手填行必须回显目录外 SPU
    const spuInput = wrapper.get('[data-testid="spu-row-input"]').element as HTMLInputElement
    expect(spuInput.value).toBe('888888')

    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()

    expect(bodies).toHaveLength(1)
    expect(bodies[0].spuBindings).toEqual([{ storeId: 77, spuId: 888888 }])
  })

  it('picker 勾选与手填重复同一 (storeId,spuId) → 提交去重不出现两条', async () => {
    const bodies = stubPicker({
      stores: [{ storeId: 1, storeName: '旗舰店', productCount: 1 }],
      productsByStore: { 1: { total: 1, page: 0, size: 10, items: [{ spuId: 9101, spuName: '蓝牙耳机', price: 120, onShelf: 1 }] } },
    })
    const { wrapper } = await setup(false, '/console/activities/new?playbook=flat')
    await wrapper.get('[data-testid="form-name"]').setValue('去重用例')

    // picker 勾 9101（占用默认空行 → dr.spu=[{1,9101}]）
    await wrapper.get('[data-testid="store-picker-toggle"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="store-picker-store-1"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="store-picker-product-9101"] input[type="checkbox"]').setValue(true)
    await wrapper.get('[data-testid="store-picker-confirm"]').trigger('click')

    // 再手填一行同样的 1/9101（storeId 默认 1）
    await wrapper.get('[data-testid="dyn-add"]').trigger('click')
    const inputs = wrapper.findAll('[data-testid="spu-row-input"]')
    await inputs[inputs.length - 1].setValue('9101')

    await wrapper.get('[data-testid="submit"]').trigger('click')
    await flushPromises()

    expect(bodies).toHaveLength(1)
    expect(bodies[0].spuBindings).toEqual([{ storeId: 1, spuId: 9101 }])
  })
})
