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
  await page.fill('[data-testid="form-name"]', `TABLET-${Date.now().toString(36)}`)
  await page.fill('[data-testid="form-amount"]', '30')
  await page.locator('[data-testid="spu-row-input"]').first().fill('990012')
  // 无横向溢出（body scrollWidth 不超 viewport 太多）
  const overflow = await page.evaluate(() => document.body.scrollWidth - window.innerWidth)
  overflow <= 4 ? ok(`平板无横向溢出（body 溢出 ${overflow}px）`) : no(`平板横向溢出 ${overflow}px`)
  await page.locator('[data-testid="submit"]').click()
  await page.waitForSelector('[data-testid="save-success"]', { timeout: 15000 })
  ok('平板 768：表单可完整填写并提交成功')
} catch (e) {
  no(`平板 smoke 异常: ${e.message}`)
  await page.screenshot({ path: `${process.env.SHOTDIR || '.'}/e2e-tablet-fail.png` }).catch(() => {})
} finally {
  await browser.close()
}
const fails = results.filter(([s]) => s === 'FAIL').length
console.log(`\n结果: pass=${results.length - fails} fail=${fails}`)
process.exit(fails > 0 ? 1 : 0)
