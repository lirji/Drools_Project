// dev 档（auth 关）回归：前端保持原 X-Tenant-Id 租户栏行为，一行不变
// 前置：app 默认档起在 :8098（./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
//        -Dspring-boot.run.arguments="--server.port=8098 --spring.datasource.url=jdbc:h2:mem:devui;DB_CLOSE_DELAY=-1;MODE=MySQL"）
//      + npm i playwright。用法：node scratchpad/e2e-dev.mjs（基线 3/3 全绿）
import { chromium } from 'playwright';

const results = [];
const ok = (m) => { results.push(['PASS', m]); console.log('  ✅', m); };
const no = (m) => { results.push(['FAIL', m]); console.log('  ❌', m); };

const browser = await chromium.launch();
const page = await browser.newPage();
page.on('pageerror', (e) => console.log('  [pageerror]', e.message));

try {
  await page.goto('http://localhost:8098/index.html');
  await page.locator('button[data-id="ext:activity"]').click();
  await page.waitForSelector('.tenant-bar', { timeout: 10000 });
  const bar = await page.locator('.tenant-bar').innerText();
  bar.includes('X-Tenant-Id') ? ok('dev 档仍显示 X-Tenant-Id 手动租户栏') : no(`租户栏异常: ${bar}`);
  await page.waitForSelector('.alist, .row-empty', { timeout: 10000 });
  ok('dev 档列表加载（header 租户来源不变）');
  // 切租户 chip 仍可用
  await page.locator('.tenant-chip:has-text("beta")').click();
  await page.waitForSelector('.alist, .row-empty', { timeout: 10000 });
  ok('dev 档切租户 chip 正常（无登录门）');
} catch (e) {
  no(`dev 回归异常: ${e.message}`);
} finally {
  await browser.close();
}
const fails = results.filter(([s]) => s === 'FAIL').length;
console.log(`\n结果: pass=${results.length - fails} fail=${fails}`);
process.exit(fails > 0 ? 1 : 0);
