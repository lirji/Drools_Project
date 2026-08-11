// 768×1024 平板 smoke（决策 D5：平板一等适配）：验证表单可完整填写提交、条件树可用。
// 用法：BASE=http://localhost:8097 node frontend/e2e/e2e-tablet-smoke.mjs（dev 档）
import { chromium } from 'playwright'

const BASE = process.env.BASE || 'http://localhost:8097'
const results = []
const ok = (m) => { results.push(['PASS', m]); console.log('  ✅', m) }
const no = (m) => { results.push(['FAIL', m]); console.log('  ❌', m) }

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 768, height: 1024 } })
try {
  await page.goto(`${BASE}/ui/console`)
  await page.waitForSelector('[data-testid="tenant-bar"]', { timeout: 15000 })
  ok('平板 768：控制台外壳可见')
  await page.locator('[data-testid="tenant-chip-acme"]').click()
  await page.locator('[data-testid="tab-new"]').click()
  await page.waitForSelector('[data-testid="form-name"]', { timeout: 10000 })
  // 表单单列下仍可填写提交
  const activityName = `TABLET-${Date.now().toString(36)}`
  await page.fill('[data-testid="form-name"]', activityName)
  await page.fill('[data-testid="form-amount"]', '30')
  await page.locator('[data-testid="spu-row-input"]').first().fill('990012')
  // 无横向溢出（body scrollWidth 不超 viewport 太多）
  const overflow = await page.evaluate(() => document.body.scrollWidth - window.innerWidth)
  overflow <= 4 ? ok(`平板无横向溢出（body 溢出 ${overflow}px）`) : no(`平板横向溢出 ${overflow}px`)
  await page.locator('[data-testid="submit"]').click()
  await page.waitForSelector('[data-testid="save-success"]', { timeout: 15000 })
  ok('平板 768：表单可完整填写并提交成功')

  // 列表 → 详情 → 优惠验证三条控制台主路径。
  await page.goto(`${BASE}/ui/console/activities`)
  await page.waitForSelector('[data-testid="list-view"]', { timeout: 10000 })
  await page.fill('[data-testid="list-search"]', activityName)
  const activityRow = page.locator('[data-testid^="activity-row-"]').filter({ hasText: activityName }).first()
  await activityRow.waitFor({ timeout: 10000 })
  await activityRow.getByRole('button', { name: '详情', exact: true }).click()
  await page.waitForSelector('[data-testid="detail-loaded"]', { timeout: 10000 })
  const detailOverflow = await page.evaluate(() => document.body.scrollWidth - window.innerWidth)
  detailOverflow <= 4 ? ok(`平板活动详情无横向溢出（body 溢出 ${detailOverflow}px）`) : no(`平板活动详情横向溢出 ${detailOverflow}px`)

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
  ok('平板 768：优惠验证可执行并展示决策结果')

  // 规则能力详情：二级菜单与工作区在 768 下纵向布局，仍可直接执行。
  await page.goto(`${BASE}/ui/demos/discount-calculate`)
  await page.waitForSelector('[data-testid="demo-panel-discount-calculate"]', { timeout: 10000 })
  const demoOverflow = await page.evaluate(() => document.body.scrollWidth - window.innerWidth)
  demoOverflow <= 4 ? ok(`平板规则能力详情无横向溢出（body 溢出 ${demoOverflow}px）`) : no(`平板规则能力详情横向溢出 ${demoOverflow}px`)
  await page.locator('[data-testid="demo-run"]').click()
  await page.waitForSelector('[data-testid="demo-status"]', { timeout: 15000 })
  ok('平板 768：规则能力可执行并展示响应')
} catch (e) {
  no(`平板 smoke 异常: ${e.message}`)
  await page.screenshot({ path: `${process.env.SHOTDIR || '.'}/e2e-tablet-fail.png` }).catch(() => {})
} finally {
  await browser.close()
}
const fails = results.filter(([s]) => s === 'FAIL').length
console.log(`\n结果: pass=${results.length - fails} fail=${fails}`)
process.exit(fails > 0 ? 1 : 0)
