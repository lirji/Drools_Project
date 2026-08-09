// PR-4 阶梯刻度尺的交互回归。
// 单测能证明「数学对」「几何绑到 DOM 上」，但证明不了「拖动卡子后人话预览真的跟着变」——
// 那是跨组件的响应式链路，只有真浏览器能验。本脚本走键盘路径（比拖拽稳定，且顺带验无障碍可达）。
//   用法：BASE=http://localhost:8095 node e2e/e2e-tier-ruler.mjs
import { chromium } from 'playwright'
const BASE = process.env.BASE || 'http://localhost:8095'
const b = await chromium.launch()
const p = await b.newPage({ viewport: { width: 1440, height: 900 } })
let pass = 0, fail = 0
const ok = (m) => { console.log('  ✅ ' + m); pass++ }
const no = (m) => { console.log('  ❌ ' + m); fail++ }
try {
  await p.goto(`${BASE}/ui/console/activities/new`)
  await p.waitForSelector('[data-testid="form-name"]', { timeout: 20000 })
  // 切到阶梯分档
  await p.getByText('阶梯分档', { exact: true }).click()
  await p.waitForSelector('[data-testid="tier-ruler"]', { timeout: 10000 })
  ok('阶梯步骤渲染出刻度尺（不再是一排 min/max/reward 输入框）')

  // 加两档，产生边界卡子
  await p.locator('[data-testid="tier-add"]').click()
  await p.locator('[data-testid="tier-add"]').click()
  const knobs = await p.locator('.knob').count()
  knobs >= 1 ? ok(`边界卡子渲染出 ${knobs} 个`) : no('没有卡子')

  const before = (await p.locator('[data-testid="tier-plain"]').textContent())?.trim()
  console.log('     预览(拖动前)：' + before)

  // 键盘驱动卡子（比拖拽稳定，且验证无障碍路径）
  await p.locator('[data-testid="tier-knob-1"]').focus()
  for (let i = 0; i < 5; i++) await p.keyboard.press('Shift+ArrowRight')
  const after = (await p.locator('[data-testid="tier-plain"]').textContent())?.trim()
  console.log('     预览(拖动后)：' + after)
  after !== before ? ok('卡子键盘可达，且人话预览实时跟随') : no('预览没有跟随卡子变化')

  // 无横向溢出
  const of = await p.evaluate(() => document.body.scrollWidth - window.innerWidth)
  of <= 1 ? ok(`刻度尺不引入横向溢出（${of}px）`) : no(`横向溢出 ${of}px`)
} catch (e) {
  no('异常: ' + e.message)
  await p.screenshot({ path: '/tmp/ruler-fail.png' }).catch(() => {})
}
console.log(`\n结果: pass=${pass} fail=${fail}`)
await b.close()
process.exit(fail ? 1 : 0)
