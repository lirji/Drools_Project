// F1 auth 档 E2E（新 Vue 前端，授权码+PKCE）：/ui/console → 路由守卫弹 /ui/login → Casdoor 登录 →
//   /ui/auth/callback 换 token → 列表(Bearer) → UI 建活动 → 登出 → 换租户 → 跨租户隔离浏览器可见。
// 断言继承旧 e2e-oidc.mjs 9 条 + 多 tab 1 条。选择器走 data-testid 契约表。
// 前置：① Casdoor :8000 且已跑 casdoor-spa-provision.sh（redirectUris 含 /ui/auth/callback）
//      ② 后端 auth 档通过网关提供（默认 :8095）且 /ui/ 已 bundle
//      ③ npm i playwright。用法：BASE=http://localhost:8095 node frontend/e2e/e2e-oidc-v2.mjs
import { chromium } from 'playwright'

const BASE = process.env.BASE || 'http://localhost:8095'
const BASE_URL = new URL(BASE)
const SHOT = (n) => `${process.env.SHOTDIR || '.'}/e2e-oidc-v2-${n}.png`
const results = []
const ok = (m) => { results.push(['PASS', m]); console.log('  ✅', m) }
const no = (m) => { results.push(['FAIL', m]); console.log('  ❌', m) }
const ts = Date.now().toString(36)
const ACME_ACT = `E2E-OIDC-v2-acme-${ts}`

async function casdoorLogin(page, user, pass) {
  await page.waitForSelector('input[type="password"]', { timeout: 20000 })
  await page.locator('form input:not([type="password"]):not([type="checkbox"])').first().fill(user)
  await page.locator('input[type="password"]').first().fill(pass)
  await page.locator('button[type="submit"]').first().click()
  try {
    await page.locator('button:has-text("授权"), button:has-text("Authorize"), button:has-text("同意")').first().click({ timeout: 4000 })
  } catch { /* 无确认页 */ }
}

async function loginAs(page, tenant, user, pass) {
  await page.goto(`${BASE}/ui/console`) // 未登录 → 守卫弹 /ui/login
  await page.waitForSelector('[data-testid="login-page"]', { timeout: 15000 })
  await page.locator('#login-tenant').fill(tenant)
  await page.locator('[data-testid="login-submit"]').click()
  await page.waitForURL(/localhost:8000/, { timeout: 20000 })
  await casdoorLogin(page, user, pass)
  await page.waitForURL(
    (url) => url.origin === BASE_URL.origin && url.pathname.startsWith('/ui/'),
    { timeout: 20000 },
  )
  await page.waitForSelector('[data-testid="auth-bar"]', { timeout: 20000 })
}

const browser = await chromium.launch()
const page = await browser.newPage()
page.on('pageerror', (e) => console.log('  [pageerror]', e.message))

try {
  // 1. 未登录 → 登录页
  await page.goto(`${BASE}/ui/console`)
  await page.waitForSelector('[data-testid="login-page"]', { timeout: 15000 })
  ok('auth 档未登录 → 路由守卫弹登录页（不打 401 接口）')
  await page.screenshot({ path: SHOT('01-login') })

  // 2. acme 登录 → 回调 → 身份条
  await loginAs(page, 'acme', 'act-alice', 'act-alice-dev-pass-01')
  const t = await page.locator('[data-testid="auth-tenant"]').innerText()
  t.includes('acme') ? ok('登录后身份条显示租户 acme（token aud 派生）') : no(`身份条租户异常: ${t}`)
  const bar = await page.locator('[data-testid="auth-bar"]').innerText()
  bar.includes('act-alice') ? ok('身份条显示操作者 act-alice') : no(`身份条操作者异常: ${bar}`)

  // 3. 列表加载（Bearer）
  await page.waitForSelector('[data-testid="list-view"]', { timeout: 10000 })
  ok('活动列表加载（Bearer 通过后端验签）')
  await page.screenshot({ path: SHOT('02-acme-list') })

  // 3b. 同一真实 Casdoor token 必须能访问独立 decision 服务；伪造租户信封必须 403。
  const decisionAuth = await page.evaluate(async () => {
    const token = JSON.parse(sessionStorage.getItem('actOidcTok') || '{}').token || ''
    const body = JSON.stringify({
      spuIdList: [9001], userId: 1, userDistrictId: null, userTags: [], orderAmount: 200, quantity: 1,
    })
    const call = (tenant) => fetch('/api/decision/spu-discount', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
        ...(tenant ? { 'X-Tenant-Id': tenant } : {}),
      },
      body,
    })
    const [valid, mismatch] = await Promise.all([call(null), call('beta')])
    return { valid: valid.status, mismatch: mismatch.status }
  })
  decisionAuth.valid === 200
    ? ok('真实 acme Bearer 可访问独立 decision 服务')
    : no(`decision 合法 token 状态异常: ${decisionAuth.valid}`)
  decisionAuth.mismatch === 403
    ? ok('decision 拒绝 token tenant 与 X-Tenant-Id 不一致（403）')
    : no(`decision tenant 冒充未按预期拒绝: ${decisionAuth.mismatch}`)

  // 4. UI 建活动
  await page.locator('[data-testid="tab-new"]').click()
  await page.waitForSelector('[data-testid="form-name"]', { timeout: 10000 })
  await page.fill('[data-testid="form-name"]', ACME_ACT)
  await page.fill('[data-testid="form-amount"]', '50')
  await page.locator('[data-testid="spu-row-input"]').first().fill('990011')
  await page.locator('[data-testid="submit"]').click()
  await page.waitForSelector('[data-testid="save-success"]', { timeout: 15000 })
  ok(`acme 经 UI 建活动成功: ${ACME_ACT}`)

  // 5. 列表可见
  await page.locator('[data-testid="tab-list"]').click()
  await page.fill('[data-testid="list-search"]', ACME_ACT)
  await page.waitForSelector(`text=${ACME_ACT}`, { timeout: 10000 })
  ok('acme 列表能看到自己刚建的活动')

  // 6. 多 tab：新 context 未登录 → 弹登录页（sessionStorage 每 tab 独立）
  const ctx2 = await browser.newContext()
  const page2 = await ctx2.newPage()
  await page2.goto(`${BASE}/ui/console`)
  await page2.waitForSelector('[data-testid="login-page"]', { timeout: 15000 })
  ok('多 tab：新 context 未登录 → 登录页（token 不跨 context 共享）')
  await ctx2.close()

  // 7. 登出 → 登录页
  await page.locator('[data-testid="logout"]').click()
  await page.waitForSelector('[data-testid="login-page"]', { timeout: 10000 })
  ok('登出后回登录页（sessionStorage token 已清）')

  // 8. beta 登录 → 看不到 acme 活动（跨租户隔离浏览器可见）
  await loginAs(page, 'beta', 'act-bob', 'act-bob-dev-pass-02')
  const t2 = await page.locator('[data-testid="auth-tenant"]').innerText()
  t2.includes('beta') ? ok('切租户重登后身份条显示 beta') : no(`身份条未显示 beta: ${t2}`)
  await page.waitForSelector('[data-testid="list-view"]', { timeout: 10000 })
  await page.fill('[data-testid="list-search"]', ACME_ACT)
  await page.waitForTimeout(500)
  const body = await page.locator('[data-testid="list-view"]').innerText()
  body.includes(ACME_ACT) ? no('❗ 隔离失败：beta 看到了 acme 的活动') : ok('beta 看不到 acme 的活动（跨租户隔离 ✔）')
  await page.screenshot({ path: SHOT('03-beta-list') })
} catch (e) {
  no(`OIDC v2 E2E 异常: ${e.message}`)
  await page.screenshot({ path: SHOT('99-fail') }).catch(() => {})
} finally {
  await browser.close()
}

const fails = results.filter(([s]) => s === 'FAIL').length
console.log(`\n结果: pass=${results.length - fails} fail=${fails}`)
process.exit(fails > 0 ? 1 : 0)
