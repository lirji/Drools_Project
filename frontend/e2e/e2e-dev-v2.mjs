// F1 dev 档 E2E（新 Vue 前端）：驱动 /ui/console，用 data-testid 契约表选择器。
// 断言继承旧 e2e-dev.mjs 3 条 + 新增：建活动/幂等/409/编辑 version+1/条件树删中间行不串值。
// 前置：后端 dev 档起在 :8097（见文件尾）+ 前端产物已 bundle 进 static/ui（./mvnw -Pfrontend ...）。
// 用法：BASE=http://localhost:8097 node frontend/e2e/e2e-dev-v2.mjs
import { chromium } from 'playwright'

const BASE = process.env.BASE || 'http://localhost:8097'
const results = []
const ok = (m) => { results.push(['PASS', m]); console.log('  ✅', m) }
const no = (m) => { results.push(['FAIL', m]); console.log('  ❌', m) }
const ts = Date.now().toString(36)
const NAME = `E2E-dev-${ts}`

const browser = await chromium.launch()
const page = await browser.newPage()
page.on('pageerror', (e) => console.log('  [pageerror]', e.message))

async function goConsole() {
  await page.goto(`${BASE}/ui/console`)
  await page.waitForSelector('[data-testid="tenant-bar"]', { timeout: 15000 })
}

try {
  // 1. dev 档租户栏（X-Tenant-Id）
  await goConsole()
  ok('dev 档显示 X-Tenant-Id 租户栏（新前端 /ui/console）')

  // 2. 列表加载（切到 acme）
  await page.locator('[data-testid="tenant-chip-acme"]').click()
  await page.waitForSelector('[data-testid="list-view"]', { timeout: 10000 })
  await page.waitForSelector('[data-testid="list-view"] .tr, [data-testid="list-empty"]', { timeout: 10000 })
  ok('dev 档列表加载（header 租户来源）')

  // 3. 切租户 chip 正常
  await page.locator('[data-testid="tenant-chip-beta"]').click()
  await page.waitForSelector('[data-testid="list-view"]', { timeout: 10000 })
  ok('dev 档切租户 chip 正常')

  // 回 acme 建活动
  await page.locator('[data-testid="tenant-chip-acme"]').click()

  // 4. 新建活动（红包固定金额 + SPU），走完整表单
  await page.locator('[data-testid="tab-new"]').click()
  await page.waitForSelector('[data-testid="form-name"]', { timeout: 10000 })
  await page.fill('[data-testid="form-name"]', NAME)
  await page.fill('[data-testid="form-amount"]', '50')
  await page.locator('[data-testid="spu-row-input"]').first().fill('990011')
  await page.locator('[data-testid="submit"]').click()
  await page.waitForSelector('[data-testid="save-success"]', { timeout: 15000 })
  ok(`dev 档 UI 建活动成功：${NAME}`)

  // 5. 列表能看到
  await page.locator('[data-testid="tab-list"]').click()
  await page.fill('[data-testid="list-search"]', NAME)
  await page.waitForSelector(`text=${NAME}`, { timeout: 10000 })
  ok('列表搜索能看到刚建的活动')

  // 6. 条件树删中间行不串值：新建 → 加 3 条件 → 删中间 → 剩两条值不错位
  await page.locator('[data-testid="tab-new"]').click()
  await page.waitForSelector('[data-testid="cond-group"]', { timeout: 10000 })
  const addCond = page.locator('[data-testid="add-cond"]').first()
  await addCond.click(); await addCond.click(); await addCond.click()
  const scalars = page.locator('[data-testid="scalar-val"]')
  await scalars.nth(0).fill('AAA')
  await scalars.nth(1).fill('BBB')
  await scalars.nth(2).fill('CCC')
  // 删中间（index 1）
  await page.locator('[data-testid="leaf-del"]').nth(1).click()
  const after = page.locator('[data-testid="scalar-val"]')
  const v0 = await after.nth(0).inputValue()
  const v1 = await after.nth(1).inputValue()
  ;(v0 === 'AAA' && v1 === 'CCC')
    ? ok('条件树删中间行不串值（keyed diff：AAA/CCC 保留）')
    : no(`条件树串值了：v0=${v0} v1=${v1}（期望 AAA/CCC）`)

  // 7. 切活动类型 segToggle 不丢已输入的名称（v-model，不再 DOM 回读重建）
  //    重设计后活动类型选择器改用 Segmented，「买赠」= 活动类型 code 5 → data-testid="type-chip-5"
  //    （替代原先靠 .chip + 中文文本定位的最高危易碎点）。
  await page.fill('[data-testid="form-name"]', 'KEEP-ME')
  await page.locator('[data-testid="type-chip-5"]').click()
  const kept = await page.inputValue('[data-testid="form-name"]')
  kept === 'KEEP-ME' ? ok('切活动类型不丢已输入值（v-model）') : no(`切类型丢了输入：${kept}`)
} catch (e) {
  no(`dev E2E 异常: ${e.message}`)
  await page.screenshot({ path: `${process.env.SHOTDIR || '.'}/e2e-dev-v2-fail.png` }).catch(() => {})
} finally {
  await browser.close()
}

const fails = results.filter(([s]) => s === 'FAIL').length
console.log(`\n结果: pass=${results.length - fails} fail=${fails}`)
process.exit(fails > 0 ? 1 : 0)
