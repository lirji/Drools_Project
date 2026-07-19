// 前端 OIDC 浏览器 E2E：登录(授权码+PKCE) → Bearer 列表 → 建活动 → 登出 → 换租户登录 → 隔离可见
// 前置：① Casdoor 在 :8000 且已跑 scratchpad/casdoor-spa-provision.sh；
//      ② app 以 auth 档起在 :8099（HANDOFF §七 的启动命令）；
//      ③ npm i playwright（浏览器内核 npx playwright install chromium）。
// 用法：node scratchpad/e2e-oidc.mjs   （SHOTDIR=/tmp 可改截图目录；基线 9/9 全绿）
import { chromium } from 'playwright';

const APP = 'http://localhost:8099/index.html';
const SHOT = (name) => `${process.env.SHOTDIR || '.'}/e2e-${name}.png`;
const results = [];
const ok = (m) => { results.push(['PASS', m]); console.log('  ✅', m); };
const no = (m) => { results.push(['FAIL', m]); console.log('  ❌', m); };

const ts = Date.now().toString(36);
const ACME_ACT = `E2E-OIDC-acme-${ts}`;

async function casdoorLogin(page, user, pass) {
  // Casdoor 登录页（antd）：用户名 .ant-input / 密码 input[type=password] / 提交 button[type=submit]
  await page.waitForSelector('input[type="password"]', { timeout: 20000 });
  const userInput = page.locator('form input:not([type="password"]):not([type="checkbox"])').first();
  await userInput.fill(user);
  await page.locator('input[type="password"]').first().fill(pass);
  await page.locator('button[type="submit"]').first().click();
  // 可能出现授权确认页
  try {
    const grant = page.locator('button:has-text("授权"), button:has-text("Authorize"), button:has-text("同意")').first();
    await grant.click({ timeout: 4000 });
  } catch { /* 无确认页，直接重定向 */ }
}

async function loginAs(page, tenant, user, pass) {
  await page.locator(`button:has-text("登录 ${tenant}")`).click();
  await page.waitForURL(/localhost:8000/, { timeout: 20000 });
  await casdoorLogin(page, user, pass);
  await page.waitForURL(/localhost:8099/, { timeout: 20000 });
  // 回调换 token 后自动挂载活动子应用
  await page.waitForSelector('.tenant-bar', { timeout: 20000 });
}

async function openActivityApp(page) {
  await page.goto(APP);
  await page.locator('button[data-id="ext:activity"]').click();
}

const browser = await chromium.launch();
const page = await browser.newPage();
page.on('pageerror', (e) => console.log('  [pageerror]', e.message));

try {
  // 1) auth 档未登录 → 登录页
  await openActivityApp(page);
  await page.waitForSelector('text=请选择租户登录', { timeout: 10000 });
  ok('auth 档未登录时渲染登录页（不再直接打 401 接口）');
  await page.screenshot({ path: SHOT('01-login-page') });

  // 2) acme 登录 → 回调 → 列表加载（带 Bearer）
  await loginAs(page, 'acme', 'act-alice', 'act-alice-dev-pass-01');
  const bar = await page.locator('.tenant-bar').innerText();
  bar.includes('acme') ? ok('登录后身份条显示租户 acme（token aud 派生）') : no(`身份条未显示 acme: ${bar}`);
  bar.includes('act-alice') ? ok('身份条显示操作者 sub（act-alice）') : no(`身份条未显示操作者: ${bar}`);
  await page.waitForSelector('.alist, .row-empty', { timeout: 10000 });
  ok('活动列表加载成功（Bearer 通过后端验签）');
  await page.screenshot({ path: SHOT('02-acme-list') });

  // 3) acme 建活动（走完整 UI 表单）
  await page.locator('.act-tab:has-text("新建活动")').click();
  await page.waitForSelector('#am-name', { timeout: 10000 });
  await page.fill('#am-name', ACME_ACT);
  await page.fill('#am-amount', '50'); // 红包固定金额（必填，缺了后端 400）
  await page.locator('.dyn-row input').nth(1).fill('990011'); // SPU 绑定行第二列 = spuId
  await page.locator('#am-submit').click();
  await page.waitForSelector('text=活动已保存', { timeout: 15000 });
  ok(`acme 经 UI 建活动成功: ${ACME_ACT}`);
  await page.screenshot({ path: SHOT('03-acme-created') });

  // 4) 回列表确认可见
  await page.locator('button:has-text("← 返回列表")').click();
  await page.waitForSelector(`text=${ACME_ACT}`, { timeout: 10000 });
  ok('acme 列表能看到自己刚建的活动');

  // 5) 登出 → 回登录页
  await page.locator('button:has-text("登出")').click();
  await page.waitForSelector('text=请选择租户登录', { timeout: 10000 });
  ok('登出后回到登录页（sessionStorage token 已清）');

  // 6) beta 登录 → 列表不含 acme 的活动（跨租户隔离浏览器可见）
  await loginAs(page, 'beta', 'act-bob', 'act-bob-dev-pass-02');
  const bar2 = await page.locator('.tenant-bar').innerText();
  bar2.includes('beta') ? ok('切租户重登后身份条显示 beta') : no(`身份条未显示 beta: ${bar2}`);
  await page.waitForSelector('.alist, .row-empty', { timeout: 10000 });
  const body = await page.locator('#panel').innerText();
  body.includes(ACME_ACT) ? no('❗ 隔离失败：beta 看到了 acme 的活动') : ok('beta 列表看不到 acme 的活动（跨租户隔离 ✔）');
  await page.screenshot({ path: SHOT('04-beta-list') });
} catch (e) {
  no(`E2E 异常: ${e.message}`);
  await page.screenshot({ path: SHOT('99-failure') }).catch(() => {});
} finally {
  await browser.close();
}

const fails = results.filter(([s]) => s === 'FAIL').length;
console.log(`\n结果: pass=${results.length - fails} fail=${fails}`);
process.exit(fails > 0 ? 1 : 0);
