// 优惠验证全玩法真链路 E2E。
//
// 目标不是复述组件单测，而是同时钉住这几条跨层契约：
//   1. header-only 身份下，独立租户创建/四眼发布的活动能被共用验证页命中；
//   2. 12 个玩法模板 + random 形态都走真实 UI 与真实 console alias；
//   3. 门槛、阶梯、数量、定向、买赠、第 N 件折、一口价与加价购的边界不会放宽；
//   4. 试算/报价不偷偷扣库存；三档宽度没有页面级横向溢出。
//
// 用法：BASE=http://localhost:8095 npm run e2e:validate
// 前提：DROOLS_AUTH_ENABLED=false（header-only 档）。脚本会先读 auth-config，避免误跑 auth 档。
import { chromium, request as playwrightRequest } from 'playwright'
import { randomBytes } from 'node:crypto'

const BASE = (process.env.BASE || 'http://localhost:8095').replace(/\/$/, '')
const STAMP = Date.now()
const NONCE = randomBytes(5).readUIntBE(0, 5)
const RUN = `${STAMP.toString(36)}-${NONCE.toString(36)}`
const TENANT = process.env.TENANT || `e2ev-${RUN}`
const AUTHOR = `e2ev-author-${RUN}`
const APPROVER = `e2ev-approver-${RUN}`
const UI_ACTOR = `e2ev-ui-${RUN}`
const BIZ_LINE = `validation-${RUN}`
const SCREENSHOT = `/tmp/e2e-validation-fail-${STAMP}.png`
const EXPECTED_SCENARIOS = [
  'flat', 'threshold', 'ladder', 'quantity', 'discount', 'tagged', 'store',
  'region', 'gift', 'second-half', 'flash', 'addon', 'random',
]
const ENDPOINTS = {
  discount: '/activity-marketing/spu-discount',
  gifts: '/activity-marketing/gifts',
  addon: '/activity-marketing/addon/options',
}

let passed = 0
let failed = 0
const created = []
const positiveCoverage = new Set()
const claimRequests = []
let fourEyesVerified = false
let browser
let browserContext
let page
let api

function brief(value, limit = 220) {
  return String(value ?? '').replace(/\s+/g, ' ').trim().slice(0, limit)
}

function must(condition, message, detail = '') {
  if (!condition) {
    throw new Error(`${message}${detail ? ` → ${brief(detail)}` : ''}`)
  }
  passed += 1
  console.log(`  ✅ ${message}`)
}

function nearly(actual, expected, epsilon = 0.000001) {
  return Number.isFinite(Number(actual)) && Math.abs(Number(actual) - expected) <= epsilon
}

function headers(actor) {
  return {
    'X-Tenant-Id': TENANT,
    'X-Actor': actor,
    'Content-Type': 'application/json',
  }
}

async function responseJson(response, label, expectedStatus = 200) {
  const text = await response.text()
  must(response.status() === expectedStatus, `${label} HTTP ${expectedStatus}`, `HTTP ${response.status()} ${text}`)
  let body = null
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    throw new Error(`${label} 返回非 JSON：${brief(text)}`)
  }
  must(body !== null && typeof body === 'object', `${label} 返回 JSON 对象`, text)
  return body
}

async function apiFetch(method, path, actor, data) {
  const options = { method, headers: headers(actor) }
  if (data !== undefined) options.data = data
  return api.fetch(path, options)
}

function condition(field, op, value) {
  return { logic: 'AND', children: [{ field, op, value }] }
}

function activityBody(label, spuId, overrides = {}) {
  const now = Date.now()
  return {
    requestId: `e2e-validation-${RUN}-${label}`,
    activityId: null,
    activityName: `验证-${label}-${RUN}`,
    bizLine: BIZ_LINE,
    activityType: 1,
    activityRule: null,
    activityStartTime: now - 3_600_000,
    activityEndTime: now + 86_400_000,
    activityAreaType: 1,
    districtIds: null,
    priority: 1,
    inventory: null,
    redPackageTakeType: 1,
    redPackageAmount: 10,
    redPackageAmountUnit: '元',
    redPackageRangeAmount: null,
    discountStrategy: 'MAX',
    eligibilityConditionTree: null,
    spuBindings: [{ storeId: 1, spuId }],
    poolRefs: null,
    gifts: null,
    redPackageMaxDiscount: null,
    ...overrides,
  }
}

async function createAndPublish(spec) {
  const createResponse = await apiFetch('POST', '/activity-marketing/create', AUTHOR, spec.body)
  const result = await responseJson(createResponse, `创建 ${spec.id}`)
  must(typeof result.activityId === 'string' && result.activityId.length > 0,
    `${spec.id} 获得 activityId`, JSON.stringify(result))
  must(Number.isInteger(result.version) && result.version > 0,
    `${spec.id} 获得正版本号`, JSON.stringify(result))

  const record = {
    id: spec.id,
    activityId: result.activityId,
    version: result.version,
    spuId: spec.spuId,
    online: false,
  }
  created.push(record)

  if (!fourEyesVerified) {
    const selfPublishResponse = await apiFetch(
      'POST',
      `/activity-marketing/${encodeURIComponent(record.activityId)}/status`,
      AUTHOR,
      { version: record.version, targetStatus: 1 },
    )
    const rejected = await responseJson(selfPublishResponse, '四眼：提交人自审发布', 409)
    must(typeof rejected.error === 'string' && rejected.error.length > 0,
      '四眼：提交人不能自审发布', JSON.stringify(rejected))
    fourEyesVerified = true
  }

  const publishResponse = await apiFetch(
    'POST',
    `/activity-marketing/${encodeURIComponent(record.activityId)}/status`,
    APPROVER,
    { version: record.version, targetStatus: 1 },
  )
  const published = await responseJson(publishResponse, `发布 ${spec.id}`)
  must(published.status === 1, `${spec.id} 已上线`, JSON.stringify(published))
  record.online = true
  return record
}

async function setOffline(record, label = record.id) {
  const response = await apiFetch(
    'POST',
    `/activity-marketing/${encodeURIComponent(record.activityId)}/status`,
    APPROVER,
    { version: record.version, targetStatus: 2 },
  )
  const body = await responseJson(response, `下线 ${label}`)
  must(body.status === 2, `${label} 已下线`, JSON.stringify(body))
  record.online = false
}

function buildSpecs() {
  // 5-byte nonce × 100 给 13 个序号留空间；总值仍远小于 JS safe integer。
  // 时间戳体现在 RUN/tenant/requestId，nonce 让同毫秒并行进程也不会共用 SPU。
  const spuBase = 1_000_000_000_000 + NONCE * 100
  const spu = (index) => spuBase + index + 1
  return [
    { id: 'flat', spuId: spu(0), body: activityBody('flat', spu(0)) },
    {
      id: 'threshold', spuId: spu(1),
      body: activityBody('threshold', spu(1), {
        redPackageAmount: 20,
        eligibilityConditionTree: condition('orderAmount', 'ge', '200'),
      }),
    },
    {
      id: 'ladder', spuId: spu(2),
      body: activityBody('ladder', spu(2), {
        redPackageAmount: null,
        redPackageRangeAmount: JSON.stringify([
          { min: 300, max: 600, reward: 50 },
          { min: 600, max: 1000, reward: 120 },
          { min: 1000, max: null, reward: 220 },
        ]),
        // 首档从 300 开始；资格闸门把「未落入任何档位」明确表达成不适用，
        // 避免历史兼容语义把一个未落档候选当成 0 元命中。
        eligibilityConditionTree: condition('orderAmount', 'ge', '300'),
      }),
    },
    {
      id: 'quantity', spuId: spu(3),
      body: activityBody('quantity', spu(3), {
        redPackageAmount: 15,
        eligibilityConditionTree: condition('quantity', 'ge', '2'),
      }),
    },
    {
      id: 'discount', spuId: spu(4),
      body: activityBody('discount', spu(4), {
        redPackageAmount: 8,
        redPackageAmountUnit: '折',
        redPackageMaxDiscount: 50,
      }),
    },
    {
      id: 'tagged', spuId: spu(5),
      body: activityBody('tagged', spu(5), {
        redPackageAmount: 30,
        eligibilityConditionTree: condition('userTags', 'contains', '高价值'),
      }),
    },
    {
      id: 'store', spuId: spu(6),
      body: activityBody('store', spu(6), {
        redPackageAmount: 12,
        eligibilityConditionTree: condition('storeId', 'eq', '1'),
      }),
    },
    {
      id: 'region', spuId: spu(7),
      body: activityBody('region', spu(7), {
        redPackageAmount: 15,
        eligibilityConditionTree: condition('userDistrictId', 'eq', '310000'),
      }),
    },
    {
      id: 'gift', spuId: spu(8),
      body: activityBody('gift', spu(8), {
        activityType: 5,
        redPackageAmount: null,
        eligibilityConditionTree: condition('orderAmount', 'ge', '500'),
        gifts: [{
          batchId: `gift-${RUN}`,
          giftName: `E2E 赠品 ${RUN}`,
          giftType: 'PHYSICAL',
          giftNum: 1,
          absoluteAmount: 29.9,
          rightType: 'GIFT',
        }],
      }),
    },
    {
      id: 'second-half', spuId: spu(9),
      body: activityBody('second-half', spu(9), {
        redPackageAmount: 5,
        redPackageAmountUnit: '件折',
        redPackageRangeAmount: JSON.stringify({ nth: 2 }),
      }),
    },
    {
      id: 'flash', spuId: spu(10),
      body: activityBody('flash', spu(10), {
        inventory: 7,
        redPackageAmount: 9.9,
        redPackageAmountUnit: '价',
      }),
    },
    {
      id: 'addon', spuId: spu(11),
      body: activityBody('addon', spu(11), {
        activityType: 6,
        inventory: 7,
        redPackageAmount: null,
        eligibilityConditionTree: condition('orderAmount', 'ge', '200'),
        gifts: [{
          batchId: `addon-${RUN}`,
          giftName: `E2E 换购品 ${RUN}`,
          giftType: 'PHYSICAL',
          giftNum: 1,
          absoluteAmount: 9.9,
          rightType: 'ADD_ON',
        }],
      }),
    },
    {
      id: 'random', spuId: spu(12),
      body: activityBody('random', spu(12), {
        redPackageTakeType: 2,
        redPackageAmount: null,
        redPackageRangeAmount: JSON.stringify({ min: 5, max: 20 }),
      }),
    },
  ]
}

async function chooseScenario(id) {
  await page.getByTestId('v-scenario').selectOption(id)
  const chosen = await page.getByTestId('v-scenario').inputValue()
  must(chosen === id, `切换到 ${id} 场景`, `实际 ${chosen}`)
  const expectedRun = id === 'gift' ? 'v-gifts' : id === 'addon' ? 'v-addon-options' : 'v-discount'
  await page.getByTestId(expectedRun).waitFor({ state: 'visible' })
}

async function fillNormal(spuId, values = {}) {
  const context = {
    amount: 100,
    quantity: 1,
    user: 1001,
    district: '',
    store: '',
    tags: '',
    ...values,
  }
  const inputs = [
    ['v-spu', spuId],
    ['v-order-amount', context.amount],
    ['v-quantity', context.quantity],
    ['v-user', context.user],
    ['v-district', context.district],
    ['v-store', context.store],
    ['v-tags', context.tags],
  ]
  for (const [testid, value] of inputs) {
    await page.getByTestId(testid).fill(value === null || value === undefined ? '' : String(value))
  }
  await page.locator('[data-testid="validate-result"]').waitFor({ state: 'detached' })
}

async function fillSingleLine(spuId, unitPrice, quantity) {
  await page.getByTestId('v-lines').waitFor({ state: 'visible' })
  let count = await page.locator('[data-testid^="v-line-"]:not([data-testid^="v-line-spu-"]):not([data-testid^="v-line-price-"]):not([data-testid^="v-line-qty-"]):not([data-testid^="v-line-remove-"]):not([data-testid="v-line-add"]):not([data-testid="v-line-summary"])').count()
  while (count > 1) {
    await page.getByTestId(`v-line-remove-${count - 1}`).click()
    count -= 1
  }
  if (count === 0) await page.getByTestId('v-line-add').click()
  await page.getByTestId('v-line-spu-0').fill(String(spuId))
  await page.getByTestId('v-line-price-0').fill(String(unitPrice))
  await page.getByTestId('v-line-qty-0').fill(String(quantity))
  await page.getByTestId('v-user').fill('1001')
  await page.getByTestId('v-district').fill('')
  await page.getByTestId('v-store').fill('')
  await page.getByTestId('v-tags').fill('')
  await page.locator('[data-testid="validate-result"]').waitFor({ state: 'detached' })
}

async function runUi(mode) {
  const endpoint = ENDPOINTS[mode]
  const runTestId = mode === 'discount' ? 'v-discount' : mode === 'gifts' ? 'v-gifts' : 'v-addon-options'
  const responsePromise = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return response.request().method() === 'POST' && url.pathname === endpoint
  }, { timeout: 20_000 })
  await page.getByTestId(runTestId).click()
  const response = await responsePromise
  const body = await responseJson(response, `UI ${mode}`)
  await page.getByTestId('validate-result').waitFor({ state: 'visible' })
  const requestBody = response.request().postDataJSON()
  must(response.request().headers()['x-tenant-id'] === TENANT,
    'UI 请求携带独立租户 header', JSON.stringify(response.request().headers()))
  must(response.request().headers()['x-actor'] === UI_ACTOR,
    'UI 请求携带独立操作者 header', JSON.stringify(response.request().headers()))
  return { body, requestBody, response }
}

async function discountCase(record, values, expectedHit, expectedAmount) {
  await chooseScenario(record.id)
  const { spuId = record.spuId, ...context } = values
  await fillNormal(spuId, context)
  const result = await runUi('discount')
  must(result.requestBody.spuIdList.length === 1 && result.requestBody.spuIdList[0] === spuId,
    `${record.id} 请求体只带唯一 SPU`, JSON.stringify(result.requestBody))
  must(result.requestBody.lines === null, `${record.id} 普通模式不伪造订单行`, JSON.stringify(result.requestBody))
  must(result.body.hit === expectedHit, `${record.id} ${expectedHit ? '正向命中' : '反向不命中'}`, JSON.stringify(result.body))
  const uiText = await page.getByTestId('validate-result').innerText()
  if (expectedHit) {
    must(result.body.hitActivityId === record.activityId,
      `${record.id} 命中预置活动`, JSON.stringify(result.body))
    if (expectedAmount !== null) {
      must(nearly(result.body.hitAmount, expectedAmount),
        `${record.id} 金额为 ${expectedAmount}`, JSON.stringify(result.body))
    }
    must(uiText.includes('命中优惠活动'), `${record.id} UI 展示命中态`, uiText)
    positiveCoverage.add(record.id)
  } else {
    must(uiText.includes('本次未命中优惠'), `${record.id} UI 展示未命中态`, uiText)
  }
  return result
}

async function giftCase(record, amount, expectedCount) {
  await chooseScenario('gift')
  await fillNormal(record.spuId, { amount, quantity: 1 })
  const result = await runUi('gifts')
  must(result.requestBody.orderAmount === amount, `gift 请求体保留 ${amount} 元边界`, JSON.stringify(result.requestBody))
  must(Array.isArray(result.body.gifts) && result.body.gifts.length === expectedCount,
    `gift ${amount} 元返回 ${expectedCount} 项`, JSON.stringify(result.body))
  const uiText = await page.getByTestId('validate-result').innerText()
  must(uiText.includes(expectedCount ? `返回 ${expectedCount} 项赠品` : '没有生效赠品'),
    `gift ${amount} 元 UI 状态正确`, uiText)
  if (expectedCount > 0) positiveCoverage.add('gift')
  return result
}

async function nthCase(record, quantity, expectedHit, expectedAmount) {
  await chooseScenario('second-half')
  await fillSingleLine(record.spuId, 100, quantity)
  const result = await runUi('discount')
  must(Array.isArray(result.requestBody.lines) && result.requestBody.lines.length === 1,
    'second-half 请求体带一条订单行', JSON.stringify(result.requestBody))
  must(result.requestBody.lines[0].spuId === record.spuId
      && result.requestBody.lines[0].unitPrice === 100
      && result.requestBody.lines[0].quantity === quantity,
    'second-half lines 保留 SPU/单价/数量', JSON.stringify(result.requestBody))
  must(result.requestBody.orderAmount === 100 * quantity && result.requestBody.quantity === quantity,
    'second-half 汇总值仅由订单行导出', JSON.stringify(result.requestBody))
  must(result.body.hit === expectedHit,
    `second-half ${quantity} 件${expectedHit ? '命中' : '不命中'}`, JSON.stringify(result.body))
  if (expectedHit) {
    must(result.body.hitActivityId === record.activityId, 'second-half 命中预置活动', JSON.stringify(result.body))
    must(nearly(result.body.hitAmount, expectedAmount),
      `second-half 减免 ${expectedAmount}`, JSON.stringify(result.body))
    positiveCoverage.add('second-half')
  }
  return result
}

async function activityDetail(record, label) {
  const response = await apiFetch(
    'GET',
    `/activity-marketing/${encodeURIComponent(record.activityId)}`,
    UI_ACTOR,
  )
  return responseJson(response, label)
}

async function runQuote() {
  const responsePromise = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return response.request().method() === 'POST' && url.pathname === '/activity-marketing/addon/quote'
  }, { timeout: 20_000 })
  await page.getByTestId('v-addon-quote').click()
  const response = await responsePromise
  const text = await response.text()
  let body = null
  try { body = text ? JSON.parse(text) : null } catch { /* 下面给出完整响应 */ }
  return { response, body, text, requestBody: response.request().postDataJSON() }
}

async function assertNoOverflow(width, state) {
  await page.evaluate(async () => {
    if (document.fonts?.ready) await document.fonts.ready
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
  })
  const layout = await page.evaluate(() => {
    const selectors = [
      '[data-testid="validate-view"]',
      '.scenario-panel',
      '.context-card',
      '.result-card',
      '[data-testid="v-lines"]',
      '[data-testid="validate-result"]',
      '[data-testid="v-addon-quote-result"]',
    ]
    return {
      innerWidth: window.innerWidth,
      body: document.body.scrollWidth,
      root: document.documentElement.scrollWidth,
      nodes: selectors.flatMap((selector) => {
        const element = document.querySelector(selector)
        return element ? [{ selector, client: element.clientWidth, scroll: element.scrollWidth }] : []
      }),
    }
  })
  const documentOverflow = Math.max(layout.body, layout.root) - layout.innerWidth
  must(documentOverflow <= 1, `${width}px ${state}无页面级横向溢出`,
    JSON.stringify({ ...layout, documentOverflow }))
  for (const node of layout.nodes) {
    const overflow = node.scroll - node.client
    must(overflow <= 1, `${width}px ${state}${node.selector} 内容未被裁切`,
      JSON.stringify({ ...node, overflow }))
  }
}

async function verifyResponsiveWidths(nthRecord, addonRecord) {
  for (const width of [390, 768, 1440]) {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 900 })
    await page.goto(`${BASE}/ui/console/validate`, { waitUntil: 'domcontentloaded' })
    await page.getByTestId('validate-view').waitFor({ state: 'visible', timeout: 20_000 })

    await chooseScenario('second-half')
    await fillSingleLine(nthRecord.spuId, 100, 2)
    await assertNoOverflow(width, '订单行态')

    await chooseScenario('addon')
    await fillNormal(addonRecord.spuId, { amount: 200 })
    const options = await runUi('addon')
    must(options.body.options?.length === 1, `${width}px 加价购结果态有唯一选项`, JSON.stringify(options.body))
    await page.getByTestId('v-addon-option-0').check()
    const quote = await runQuote()
    must(quote.response.status() === 200 && quote.body?.ok === true,
      `${width}px 加价购结果态 quote 成功`, quote.text)
    await page.getByTestId('v-addon-quote-result').waitFor({ state: 'visible' })
    await assertNoOverflow(width, '加价购报价结果态')
  }
}

try {
  console.log(`\n优惠验证全玩法 E2E · BASE=${BASE} · tenant=${TENANT}`)
  browser = await chromium.launch()
  browserContext = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await browserContext.addInitScript(({ tenant, actor }) => {
    localStorage.setItem('actTenant', tenant)
    localStorage.setItem('actActor', actor)
  }, { tenant: TENANT, actor: UI_ACTOR })
  page = await browserContext.newPage()
  page.on('request', (request) => {
    if (new URL(request.url()).pathname.endsWith('/claim')) claimRequests.push(request.url())
  })
  api = await playwrightRequest.newContext({ baseURL: BASE })

  const authResponse = await api.get('/activity-marketing/auth-config')
  const authConfig = await responseJson(authResponse, '读取 auth-config')
  must(authConfig.authEnabled === false,
    '当前为 header-only 环境', `authEnabled=${authConfig.authEnabled}; 请用 DROOLS_AUTH_ENABLED=false 启动`)

  const specs = buildSpecs()
  const records = new Map()
  for (const spec of specs) records.set(spec.id, await createAndPublish(spec))
  must(new Set(specs.map((spec) => spec.spuId)).size === specs.length,
    '每个活动使用本次运行唯一 SPU')

  await page.goto(`${BASE}/ui/console/validate`, { waitUntil: 'domcontentloaded' })
  await page.getByTestId('validate-view').waitFor({ state: 'visible', timeout: 20_000 })
  const scenarioValues = await page.getByTestId('v-scenario').locator('option').evaluateAll(
    (options) => options.map((option) => option.value),
  )
  // 集合比较而非全序比较：目录里纯展示性的卡片重排不该打红这条 e2e——
  // 这条断言守的是「每个可建玩法 + random 都派生出了场景」，不是玩法卡的陈列顺序。
  must(scenarioValues.length === EXPECTED_SCENARIOS.length
      && EXPECTED_SCENARIOS.every((id) => scenarioValues.includes(id)),
    '验证页场景与 12 个玩法 + random 完整对齐（集合，不校验顺序）', JSON.stringify(scenarioValues))
  must((await page.getByTestId('v-scenario-note').innerText()).includes('不保证命中'),
    '页面明示场景只准备上下文、不强制命中')

  await discountCase(records.get('flat'), { amount: 100, spuId: records.get('flat').spuId + 50 }, false, 0)
  await discountCase(records.get('flat'), { amount: 100 }, true, 10)

  await discountCase(records.get('threshold'), { amount: 199.99 }, false, 0)
  await discountCase(records.get('threshold'), { amount: 200 }, true, 20)

  await discountCase(records.get('ladder'), { amount: 299.99 }, false, 0)
  await discountCase(records.get('ladder'), { amount: 300 }, true, 50)
  await discountCase(records.get('ladder'), { amount: 600 }, true, 120)
  await discountCase(records.get('ladder'), { amount: 1000 }, true, 220)

  await discountCase(records.get('quantity'), { quantity: 1 }, false, 0)
  await discountCase(records.get('quantity'), { quantity: 2 }, true, 15)

  await discountCase(records.get('discount'), { amount: 200 }, true, 40)
  await discountCase(records.get('discount'), { amount: 400 }, true, 50)

  await discountCase(records.get('tagged'), { tags: '普通用户' }, false, 0)
  await discountCase(records.get('tagged'), { tags: '高价值,vip' }, true, 30)
  await discountCase(records.get('store'), { store: 2 }, false, 0)
  await discountCase(records.get('store'), { store: 1 }, true, 12)
  await discountCase(records.get('region'), { district: '110000' }, false, 0)
  await discountCase(records.get('region'), { district: '310000' }, true, 15)

  await giftCase(records.get('gift'), 499, 0)
  const giftPositive = await giftCase(records.get('gift'), 500, 1)
  must(giftPositive.body.gifts[0].giftName.includes(RUN),
    'gift 500 返回本次创建的赠品', JSON.stringify(giftPositive.body))

  await nthCase(records.get('second-half'), 1, false, 0)
  await nthCase(records.get('second-half'), 2, true, 50)

  const flash = records.get('flash')
  const inventoryBefore = Number((await activityDetail(flash, '读取秒杀前详情')).manage.inventory)
  await discountCase(flash, { amount: 9 }, false, 0)
  await discountCase(flash, { amount: 100 }, true, 90.1)
  const breakdown = await page.getByTestId('v-price-breakdown').innerText()
  must(breakdown.includes('100.00') && breakdown.includes('90.10') && breakdown.includes('9.90'),
    'flash UI 展示原价/减免/应付', breakdown)
  const inventoryNote = await page.getByTestId('v-inventory-note').innerText()
  must(inventoryNote.includes('不会扣减或占用秒杀库存'), 'flash UI 明示仅报价不扣库存', inventoryNote)
  const inventoryAfter = Number((await activityDetail(flash, '读取秒杀后详情')).manage.inventory)
  must(inventoryBefore === 7 && inventoryAfter === inventoryBefore,
    'flash 试算前后库存不变', `before=${inventoryBefore}, after=${inventoryAfter}`)

  const addon = records.get('addon')
  const addonInventoryBefore = Number((await activityDetail(addon, '读取加价购验证前详情')).manage.inventory)
  await chooseScenario('addon')
  await fillNormal(addon.spuId, { amount: 199 })
  const addonNegative = await runUi('addon')
  must(Array.isArray(addonNegative.body.options) && addonNegative.body.options.length === 0,
    'addon 199 元不满足资格且无选项', JSON.stringify(addonNegative.body))
  must((await page.getByTestId('validate-result').innerText()).includes('没有可用换购选项'),
    'addon 反向 UI 展示空态')

  await fillNormal(addon.spuId, { amount: 200 })
  const addonPositive = await runUi('addon')
  must(Array.isArray(addonPositive.body.options) && addonPositive.body.options.length === 1,
    'addon 200 元返回一个用户可选项', JSON.stringify(addonPositive.body))
  const option = addonPositive.body.options[0]
  must(option.activityId === addon.activityId && nearly(option.addOnPrice, 9.9),
    'addon option 来自本次活动且加价 9.9', JSON.stringify(option))
  must((await page.getByTestId('v-inventory-note').innerText()).includes('不会占用换购库存'),
    'addon 页面明示 options/quote 不占库存')
  must(await page.getByTestId('v-addon-quote').isDisabled(),
    'addon 不替用户预选，未勾 radio 前禁止报价')
  await page.getByTestId('v-addon-option-0').check()
  const quote = await runQuote()
  must(quote.response.status() === 200, 'addon 用户选项后 quote 返回 200', quote.text)
  const quoteUrl = new URL(quote.response.url())
  must(quoteUrl.searchParams.get('activityId') === addon.activityId
      && quoteUrl.searchParams.get('item') === option.itemName,
    'addon quote 只提交用户所选 activityId + item', quote.response.url())
  must(quote.requestBody.orderAmount === 200 && quote.requestBody.spuIdList[0] === addon.spuId,
    'addon quote 重新提交当前订单上下文', JSON.stringify(quote.requestBody))
  must(quote.body?.ok === true && quote.body.activityId === addon.activityId && nearly(quote.body.addOnPrice, 9.9),
    'addon 权威报价忽略客户端价格、返回服务端配置', quote.text)
  must(Array.isArray(quote.body?.traces) && quote.body.traces.some((trace) => trace.includes('权威报价')),
    'addon 成功 quote 返回第二阶段轨迹', quote.text)
  await page.getByTestId('v-addon-quote-result').waitFor({ state: 'visible' })
  must((await page.getByTestId('v-addon-quote-result').innerText()).includes('未占库存'),
    'addon 报价结果明示未占库存')
  must((await page.locator('.trace-panel').innerText()).includes('权威报价'),
    'addon 页面展示第二阶段权威报价轨迹')
  positiveCoverage.add('addon')

  await verifyResponsiveWidths(records.get('second-half'), addon)

  const forged = await apiFetch(
    'POST',
    `/activity-marketing/addon/quote?activityId=${encodeURIComponent(addon.activityId)}&item=${encodeURIComponent(`伪造换购品-${RUN}`)}`,
    UI_ACTOR,
    addonPositive.requestBody,
  )
  const forgedBody = await responseJson(forged, 'addon 伪造 item 报价', 409)
  must(forgedBody.ok === false && typeof forgedBody.reason === 'string'
      && Array.isArray(forgedBody.traces) && forgedBody.traces.some((trace) => trace.includes('报价拒绝')),
    'addon 伪造 item 被 409 fail-closed', JSON.stringify(forgedBody))

  await setOffline(addon, 'addon（模拟两阶段间失效）')
  const expiredQuote = await runQuote()
  must(expiredQuote.response.status() === 409 && expiredQuote.body?.ok === false
      && Array.isArray(expiredQuote.body?.traces)
      && expiredQuote.body.traces.some((trace) => trace.includes('报价拒绝')),
    'addon 选项失效后真实 UI quote 收到 409', expiredQuote.text)
  await page.getByTestId('v-addon-conflict').waitFor({ state: 'visible' })
  must((await page.getByTestId('v-addon-conflict').innerText()).includes('报价已失效（409）'),
    'addon UI 单独展示 409 失效态')
  must((await page.locator('.trace-panel').innerText()).includes('报价拒绝'),
    'addon UI 409 展示本次拒绝轨迹而非旧 options 轨迹')
  const addonInventoryAfter = Number((await activityDetail(addon, '读取加价购验证后详情')).manage.inventory)
  must(addonInventoryBefore === 7 && addonInventoryAfter === addonInventoryBefore,
    'addon options/quote/409 前后库存不变',
    `before=${addonInventoryBefore}, after=${addonInventoryAfter}`)
  must(claimRequests.length === 0, '验证页全流程从未请求库存 claim', JSON.stringify(claimRequests))

  const random = records.get('random')
  const randomFirst = await discountCase(random, { amount: 321, quantity: 3, user: 2026 }, true, null)
  const firstAmount = Number(randomFirst.body.hitAmount)
  must(firstAmount >= 5 && firstAmount <= 20, 'random 金额落在 [5,20] 区间', firstAmount)
  const randomSecond = await discountCase(random, { amount: 321, quantity: 3, user: 2026 }, true, firstAmount)
  must(nearly(randomSecond.body.hitAmount, firstAmount),
    'random 同用户同购物车复跑金额一致', JSON.stringify({ firstAmount, second: randomSecond.body.hitAmount }))

  must(EXPECTED_SCENARIOS.every((id) => positiveCoverage.has(id)),
    '12 个玩法 + random 每项至少一个正向', JSON.stringify([...positiveCoverage]))

} catch (error) {
  failed += 1
  console.error(`  ❌ ${error instanceof Error ? error.message : String(error)}`)
  if (page) {
    await page.screenshot({ path: SCREENSHOT, fullPage: true }).then(
      () => console.error(`  📷 失败截图：${SCREENSHOT}`),
      () => {},
    )
  }
} finally {
  if (api) {
    for (const record of [...created].reverse()) {
      try {
        const response = await apiFetch(
          'POST',
          `/activity-marketing/${encodeURIComponent(record.activityId)}/status`,
          APPROVER,
          { version: record.version, targetStatus: 2 },
        )
        if (response.status() !== 200) {
          console.warn(`  ⚠ cleanup ${record.id}/${record.activityId}: HTTP ${response.status()} ${brief(await response.text())}`)
        }
      } catch (error) {
        console.warn(`  ⚠ cleanup ${record.id}/${record.activityId}: ${brief(error)}`)
      }
    }
    await api.dispose()
  }
  if (browser) await browser.close()
}

console.log(`\n结果: pass=${passed} fail=${failed}`)
if (failed) process.exitCode = 1
