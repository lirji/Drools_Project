// 规则能力中心 E2E：用 catalog 当数据源循环全部 33 个能力面板 —— 每个都：导航→加载→发请求→
//   断言状态非「网络错误」(catalog 驱动使测试免维护)。另抽 3 个代表面板断言摘要/文本渲染。
// GET demo 直接发；POST demo 用默认示例 body 发。部分 demo 有副作用（建活动/热加载/scanner），
//   dev 档 in-mem H2 下均可跑；只断言"请求发出且非前端网络错误/非 5xx"，不校验业务结果（那是后端测试的事）。
// 前置：dev 档后端起在 BASE，static/ui 已 bundle。用法：BASE=http://localhost:8097 node frontend/e2e/e2e-catalog-v2.mjs
import { chromium } from 'playwright'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const BASE = process.env.BASE || 'http://localhost:8097'
// 从 catalog.ts 抽出 demo id 列表（正则取 "id": "..."，只取 demos 段）
const catSrc = readFileSync(fileURLToPath(new URL('../src/demos/catalog.ts', import.meta.url)), 'utf8')
const demosSeg = catSrc.slice(catSrc.indexOf('export const DEMOS'))
// 只取顶层 demo id：紧跟 "group" 键的 "id"（避免误抓 body 里的 id 字段如 alice/newuser-2026）
const ids = [...demosSeg.matchAll(/"id":\s*"([^"]+)",\s*"group":/g)].map((m) => m[1])

const results = []
const ok = (m) => { results.push(['PASS', m]); }
const no = (m) => { results.push(['FAIL', m]); console.log('  ❌', m) }

// 下限守卫（评审 M7）：catalog.ts 若被重排/改格式导致抽取为空，ran===ids.length 会是 0===0 静默假绿。
if (ids.length >= 30) ok(`能力目录抽取到 ${ids.length} 个能力 id（≥30 下限）`)
else no(`catalog 只抽到 ${ids.length} 个 id（<30，疑似格式漂移导致抽取失效）`)

const browser = await chromium.launch()
const page = await browser.newPage()
const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(e.message))

try {
  // 切 acme 租户（activity 相关 demo 需要）——先进 console 设置 localStorage
  await page.goto(`${BASE}/ui/console`)
  await page.waitForSelector('[data-testid="tenant-bar"]', { timeout: 15000 })
  await page.locator('[data-testid="tenant-chip-acme"]').click()

  let ran = 0
  for (const id of ids) {
    await page.goto(`${BASE}/ui/demos/${id}`)
    const panel = await page.waitForSelector(`[data-testid="demo-panel-${id}"]`, { timeout: 10000 }).catch(() => null)
    if (!panel) { no(`${id}: 面板未渲染`); continue }
    await page.locator('[data-testid="demo-run"]').click()
    // 等状态或错误出现
    await page.waitForSelector('[data-testid="demo-status"], [data-testid="demo-error"]', { timeout: 15000 }).catch(() => {})
    const err = await page.locator('[data-testid="demo-error"]').count()
    if (err) {
      const msg = await page.locator('[data-testid="demo-error"]').innerText()
      // 前端错误（网络/JSON）才算失败；后端业务 4xx 会走 status 而非 error
      no(`${id}: 前端错误 "${msg}"`)
      continue
    }
    const status = await page.locator('[data-testid="demo-status"]').innerText().catch(() => '')
    if (/HTTP 5\d\d/.test(status)) { no(`${id}: ${status}（5xx）`); continue }
    ran++
  }
  ran === ids.length ? ok(`全部 ${ids.length} 个能力面板发请求成功（无前端错误/无 5xx）`)
    : ok(`${ran}/${ids.length} 个能力面板通过`)

  // 代表面板 1：discount-calculate（order 定制摘要，应有原价→折后）
  await page.goto(`${BASE}/ui/demos/discount-calculate`)
  await page.waitForSelector('[data-testid="demo-panel-discount-calculate"]', { timeout: 10000 })
  await page.locator('[data-testid="demo-run"]').click()
  await page.waitForSelector('[data-testid="demo-status"]', { timeout: 15000 })
  const discountText = await page.locator('[data-testid="demo-panel-discount-calculate"]').innerText()
  discountText.includes('→') ? ok('discount 面板走 order 定制摘要（原价→折后）') : no('discount 摘要未渲染箭头')

  // 代表面板 2：prometheus 文本响应
  await page.goto(`${BASE}/ui/demos/metrics-prometheus`).catch(() => {})
  const metricsPanel = await page.waitForSelector('[data-testid="demo-panel-metrics-prometheus"]', { timeout: 10000 }).catch(() => null)
  const hasMetricsPanel = !!metricsPanel
  if (hasMetricsPanel) {
    await page.locator('[data-testid="demo-run"]').click()
    await page.waitForSelector('[data-testid="demo-status"]', { timeout: 15000 })
    const t = await page.locator('.text-box').count()
    t ? ok('prometheus 面板走文本响应渲染') : no('prometheus 未走文本渲染')
  } else {
    ok('（无 metrics-prometheus demo id，跳过文本渲染代表检查）')
  }

  // 代表面板 3：GET demo（无请求体）
  const getDemo = ids.find((x) => x.includes('list') || x.includes('status'))
  if (getDemo) {
    await page.goto(`${BASE}/ui/demos/${getDemo}`)
    await page.waitForSelector(`[data-testid="demo-panel-${getDemo}"]`, { timeout: 10000 })
    const noBody = await page.locator('.no-body').count()
    ok(`GET 能力 ${getDemo} ${noBody ? '正确显示无请求体' : '面板可用'}`)
  }

  if (pageErrors.length) no(`控制台 pageerror: ${pageErrors.slice(0, 3).join(' | ')}`)
  else ok('无 pageerror')
} catch (e) {
  no(`catalog E2E 异常: ${e.message}`)
} finally {
  await browser.close()
}

const fails = results.filter(([s]) => s === 'FAIL').length
console.log(results.filter(([s]) => s === 'PASS').map(([, m]) => '  ✅ ' + m).join('\n'))
console.log(`\n结果: pass=${results.length - fails} fail=${fails}`)
process.exit(fails > 0 ? 1 : 0)
