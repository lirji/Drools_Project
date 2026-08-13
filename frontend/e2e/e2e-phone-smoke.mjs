// 390×844 手机 smoke（重设计）：<768 侧栏为 off-canvas 抽屉——验证汉堡→抽屉→导航→表单可填提交、无横向溢出。
// 覆盖 e2e-tablet-smoke（768 docked，不点汉堡）覆盖不到的抽屉行为。
// 用法：BASE=http://localhost:8097 node frontend/e2e/e2e-phone-smoke.mjs（dev 档，默认租户 acme）
import { chromium } from 'playwright'

const BASE = process.env.BASE || 'http://localhost:8097'
const results = []
const ok = (m) => { results.push(['PASS', m]); console.log('  ✅', m) }
const no = (m) => { results.push(['FAIL', m]); console.log('  ❌', m) }

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 390, height: 844 } })
try {
  await page.goto(`${BASE}/ui/console`)
  // <768：汉堡出现、侧栏离屏
  await page.waitForSelector('[data-testid="nav-toggle"]', { state: 'visible', timeout: 15000 })
  ok('手机 390：汉堡按钮可见（侧栏收成抽屉）')
  // 无横向溢出（抽屉 transform 离屏，不撑宽）
  const overflow = await page.evaluate(() => document.body.scrollWidth - window.innerWidth)
  overflow <= 4 ? ok(`手机无横向溢出（body 溢出 ${overflow}px）`) : no(`手机横向溢出 ${overflow}px`)
  // 开抽屉 → 点「新建活动」
  await page.locator('[data-testid="nav-toggle"]').click()
  await page.waitForTimeout(300) // 抽屉动效
  await page.locator('[data-testid="tab-new"]').click()
  await page.waitForSelector('[data-testid="form-name"]', { timeout: 10000 })
  ok('手机 390：抽屉可开合、导航进新建活动')
  // 单列表单可填写提交（默认租户 acme）
  await page.fill('[data-testid="form-name"]', `PHONE-${Date.now().toString(36)}`)
  await page.fill('[data-testid="form-amount"]', '20')
  await page.locator('[data-testid="spu-row-input"]').first().fill('990012')

  // 地域级联在 390px 下的横向溢出。**这是编辑页唯一的手机溢出采样点**——
  // 上面第 19 行那次量的是列表页，tablet 那条量的是 768（宽得多）。
  // 三栏并排在 390 上必溢出，所以它必须塌成单栏 + 面包屑；量完要显式收起，
  // 否则展开的面板会把 submit 顶出视口，Playwright 的可操作性检查直接超时。
  await page.selectOption('[data-testid="form-area-type"]', '2')
  await page.waitForSelector('[data-testid="district-toggle"]', { timeout: 10000 })
  await page.locator('[data-testid="district-toggle"]').click()
  // 等到真实选项而不是面板根节点：根节点在字典到达前就已经在了，那时量到的是 Skeleton，
  // 三栏塌没塌根本没被测到，断言会静默通过（同 e2e-visual-guard A-13 那段）。
  await page.waitForSelector('[data-testid="district-opt-440000"]', { timeout: 15000 })
  const districtOverflow = await page.evaluate(() => document.body.scrollWidth - window.innerWidth)
  districtOverflow <= 4
    ? ok(`手机 390：地域级联展开后无横向溢出（${districtOverflow}px）`)
    : no(`手机 390：地域级联横向溢出 ${districtOverflow}px —— 三栏没塌成单栏？`)
  await page.locator('[data-testid="district-toggle"]').click()
  await page.selectOption('[data-testid="form-area-type"]', '1') // 回到全国，不给提交加必填项

  await page.locator('[data-testid="submit"]').click()
  await page.waitForSelector('[data-testid="save-success"]', { timeout: 15000 })
  ok('手机 390：表单可完整填写并提交成功')

  await page.goto(`${BASE}/ui/console/validate`)
  await page.waitForSelector('[data-testid="validate-view"]', { timeout: 10000 })
  await page.fill('[data-testid="v-spu"]', '990011')
  // 这条 smoke 测的是**布局**（视口/抽屉/无横向溢出），不是决策平面。
  // 验证页默认打 decision 服务，而本脚本默认 BASE 是裸 console（:8097，没有 decision 进程），
  // 那样会停在「决策服务不可达」而拿不到结果卡。所以显式切到走库平面——
  // 这是测试**声明自己在测什么**，不是给页面加静默降级（后者会让人拿走库结论当线上结论）。
  await page.locator('[data-testid="v-plane-console"]').click()
  await page.locator('[data-testid="v-discount"]').click()
  await page.waitForSelector('[data-testid="validate-result"]', { timeout: 15000 })
  const validateOverflow = await page.evaluate(() => document.body.scrollWidth - window.innerWidth)
  validateOverflow <= 4 ? ok(`手机优惠验证无横向溢出（body 溢出 ${validateOverflow}px）`) : no(`手机优惠验证横向溢出 ${validateOverflow}px`)

  // 规则能力中心：分组目录可搜索，详情页在 390 下不横向溢出。
  await page.goto(`${BASE}/ui/demos`)
  await page.waitForSelector('[data-testid="demo-home"]', { timeout: 10000 })
  await page.fill('[data-testid="demo-search"]', 'CEP')
  await page.waitForSelector('[data-testid="demo-home-fraud-check"]', { timeout: 5000 })
  ok('手机 390：规则能力目录可搜索')
  await page.locator('[data-testid="demo-home-fraud-check"]').click()
  await page.waitForSelector('[data-testid="demo-panel-fraud-check"]', { timeout: 10000 })
  const demoOverflow = await page.evaluate(() => document.body.scrollWidth - window.innerWidth)
  demoOverflow <= 4 ? ok(`手机规则能力详情无横向溢出（body 溢出 ${demoOverflow}px）`) : no(`手机规则能力详情横向溢出 ${demoOverflow}px`)
} catch (e) {
  no(`手机 smoke 异常: ${e.message}`)
  await page.screenshot({ path: `${process.env.SHOTDIR || '.'}/e2e-phone-fail.png` }).catch(() => {})
} finally {
  await browser.close()
}
const fails = results.filter(([s]) => s === 'FAIL').length
console.log(`\n结果: pass=${results.length - fails} fail=${fails}`)
process.exit(fails > 0 ? 1 : 0)
