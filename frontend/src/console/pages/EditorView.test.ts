import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useAuthStore } from '@/auth/useAuthStore'
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
    activityTypes: [{ code: 1, label: '红包' }, { code: 5, label: '买赠' }],
    statuses: [], distributionModes: [{ code: 1, label: '固定金额' }, { code: 2, label: '随机金额' }],
    strategies: ['MAX'],
  }

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function dictOk() {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(200, FIELD_DICT)))
  }

  it('?playbook=ladder 把阶梯档位填进表单并说明这是模板起点', async () => {
    dictOk()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=ladder')

    const banner = wrapper.get('[data-testid="playbook-applied"]')
    expect(banner.text()).toContain('阶梯满减')
    expect(banner.text()).toContain('每一项都可以改')
    // 阶梯模式下人话预览应已经在讲三档
    expect(wrapper.get('[data-testid="tier-plain"]').text()).toContain('300')
  })

  it('?playbook=threshold 预填固定金额，且条件树带上订单金额门槛', async () => {
    dictOk()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=threshold')

    expect((wrapper.get('[data-testid="form-amount"]').element as HTMLInputElement).value).toBe('20')
    expect(wrapper.findAll('[data-testid="cond-leaf"]').length).toBeGreaterThan(0)
  })

  it('「随机金额」已可选，选中后换成区间输入并说明是确定性随机', async () => {
    dictOk()
    const { wrapper } = await setup(false, '/console/activities/new?playbook=flat')

    const select = wrapper.get('[data-testid="form-take-type"]')
    const random = select.findAll('option').find((o) => o.text().includes('随机金额'))
    expect(random, '随机金额选项应存在').toBeTruthy()
    expect(random!.attributes('disabled'), '决策链路已接入，不该再禁用').toBeUndefined()

    // 选中随机 → 固定金额输入让位给区间两端
    await select.setValue(2)
    expect(wrapper.find('[data-testid="form-range-min"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="form-range-max"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="form-amount"]').exists()).toBe(false)
    // 必须写明它不是真抽奖——否则运营会以为同一用户多刷几次能拿到不同金额
    expect(wrapper.text()).toContain('确定性随机')
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

    it('五种形态都能用 chip 切到——不存在只进不出的单向门', async () => {
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
