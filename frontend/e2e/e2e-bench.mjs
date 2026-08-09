// PR-5 活动工作台的浏览器回归。
//
// 单测能证明「归并算对了」「选择模型收敛了」，证明不了这几件事：
//   · 批量四段流程（勾选 → 压出操作条 → 影响摘要 → 回执）真的能一路走通
//   · 批量下线打到的是**正在服务的那一版**——线上 v1 与草稿 v2 并存时，
//     打错版本的表现是「回执报成功、活动照样在发钱」，只有跑一遍真链路才看得出来
//   · 密度切换真的落到 <html data-density> 上并且刷新还在
//   · 侧板 Esc 可关、且不把 body 撑出横滚
//
//   用法：BASE=http://localhost:8095 node e2e/e2e-bench.mjs
import { chromium } from 'playwright'

const BASE = process.env.BASE || 'http://localhost:8095'
const TENANT = process.env.TENANT || 'acme'
const STAMP = Date.now()

const b = await chromium.launch()
const p = await b.newPage({ viewport: { width: 1440, height: 900 } })
let pass = 0, fail = 0
const ok = (m) => { console.log('  ✅ ' + m); pass++ }
const no = (m) => { console.log('  ❌ ' + m); fail++ }
/** 语句式断言：不用三元，避免「no(...) 换行接正则字面量」被 ASI 解析成除法 */
const check = (cond, msg, detail = '') => { if (cond) ok(msg); else no(msg + (detail ? ' → ' + detail : '')) }
const brief = (s) => String(s).replace(/\s+/g, ' ').slice(0, 140)

// 提交人与审批人刻意用不同身份：四眼开关若打开，同一个人不能发布自己提交的活动
const asAuthor = { 'X-Tenant-Id': TENANT, 'X-Actor': 'e2e-author', 'Content-Type': 'application/json' }
const asApprover = { 'X-Tenant-Id': TENANT, 'X-Actor': 'e2e-approver', 'Content-Type': 'application/json' }

function activityBody(name, activityId = null) {
  const now = Date.now()
  return {
    requestId: null, activityId, activityName: name, bizLine: 'bench', activityType: 1,
    activityRule: null, activityStartTime: now - 3_600_000, activityEndTime: now + 30 * 86_400_000,
    activityAreaType: 1, districtIds: null, priority: 1, inventory: 500,
    redPackageTakeType: 1, redPackageAmount: 10, redPackageAmountUnit: '元',
    redPackageRangeAmount: null, discountStrategy: 'MAX',
    eligibilityConditionTree: null,
    spuBindings: [{ storeId: 1, spuId: 900000 + Math.floor(Math.random() * 90000) }],
    poolRefs: null, gifts: null,
  }
}

async function createAndPublish(name) {
  const created = await p.request.post(`${BASE}/activity-marketing/create`,
    { headers: asAuthor, data: activityBody(name) })
  const { activityId, version } = await created.json()
  await p.request.post(`${BASE}/activity-marketing/${activityId}/status`,
    { headers: asApprover, data: { version, targetStatus: 1 } })
  return activityId
}

try {
  // ---- 备数据：两个已上线活动，其中一个再编辑出草稿（线上 v1 + 草稿 v2 并存） ----
  const nameA = `工作台A-${STAMP}`
  const nameB = `工作台B-${STAMP}`
  const idA = await createAndPublish(nameA)
  const idB = await createAndPublish(nameB)
  await p.request.post(`${BASE}/activity-marketing/create`,
    { headers: asAuthor, data: activityBody(`${nameA}-改`, idA) })   // → 草稿 v2

  // ---- 设租户后进工作台 ----
  await p.goto(`${BASE}/ui/console`)
  await p.waitForSelector('[data-testid="tenant-bar"]', { timeout: 20000 })
  await p.locator(`[data-testid="tenant-chip-${TENANT}"]`).click()
  await p.waitForSelector('[data-testid="list-view"]', { timeout: 20000 })

  // ---- ① 归并：线上 v1 与草稿 v2 只出一行 ----
  await p.locator('[data-testid="list-search"]').fill(nameA)
  await p.waitForSelector(`[data-testid="activity-row-${idA}"]`, { timeout: 10000 })
  const rowCount = await p.locator(`[data-testid="activity-row-${idA}"]`).count()
  const rowText = await p.locator(`[data-testid="activity-row-${idA}"]`).first().innerText()
  check(rowCount === 1, `线上版与草稿版归并成一行（activity-row-${idA} 命中 1 次）`,
    `实际渲染 ${rowCount} 行，:key 与 testid 都在重复`)
  check(/草稿 v2/.test(rowText), '行内标出「草稿 v2」，运营知道线上版之上还压着一版', brief(rowText))
  check(/生效中/.test(rowText), '该活动当前是「生效中」', brief(rowText))

  // ---- ② 批量四段流程 ----
  // 按本次运行的时间戳过滤，而不是「工作台」——反复跑会累积同名活动，
  // 超过一页（20）后第二个活动会被翻到第 2 页，行根本不渲染
  await p.locator('[data-testid="list-search"]').fill(`-${STAMP}`)
  await p.waitForSelector(`[data-testid="activity-row-${idB}"]`, { timeout: 10000 })
  await p.locator(`[data-testid="row-check-${idA}"]`).click()
  await p.locator(`[data-testid="row-check-${idB}"]`).click()

  await p.waitForSelector('[data-testid="bulk-bar"]', { timeout: 5000 })
  const cnt = await p.locator('[data-testid="bulk-count"]').innerText()
  check(/2/.test(cnt), `批量条压出并计数正确（${cnt.trim()}）`, brief(cnt))

  await p.locator('[data-testid="bulk-offline"]').click()
  await p.waitForSelector('[data-testid="bulk-confirm"]', { timeout: 5000 })
  const impact = await p.locator('[data-testid="bulk-confirm"]').innerText()
  check(/正在生效/.test(impact), '影响摘要点明「其中几个正在生效，下线后立即停止参与决策」', brief(impact))

  await p.locator('[data-testid="bulk-confirm-ok"]').click()

  // ---- ③ 回执 ----
  await p.waitForSelector('[data-testid="toast-view-receipt"]', { timeout: 15000 })
  ok('批量结果以**不自动消失**的回执 toast 呈现，带「查看回执」')
  await p.locator('[data-testid="toast-view-receipt"]').click()
  await p.waitForSelector('[data-testid="bench-receipt"]', { timeout: 5000 })
  const receipt = await p.locator('[data-testid="bench-receipt"]').innerText()
  check(/2\s*成功/.test(receipt), '回执列出 2 成功', brief(receipt))
  await p.locator('[data-testid="side-panel-close"]').click()

  // ---- ④ 版本正确性的现场证据 ----
  await p.locator('[data-testid="list-refresh"]').click()
  await p.locator('[data-testid="list-search"]').fill(nameA)
  await p.waitForSelector(`[data-testid="activity-row-${idA}"]`, { timeout: 10000 })
  const after = await p.locator(`[data-testid="activity-row-${idA}"]`).first().innerText()
  check(!/生效中/.test(after),
    '批量下线后该活动不再「生效中」——打到的是正在服务的那一版，不是草稿',
    '活动仍生效中：批量下线打到了草稿，线上还在发钱 · ' + brief(after))

  // ---- ⑤ 密度切换真切 <html> 且刷新保持 ----
  await p.locator('[data-testid="density-compact"]').click()
  const d1 = await p.evaluate(() => document.documentElement.getAttribute('data-density'))
  check(d1 === 'compact', '密度切换落到 <html data-density="compact">', `data-density=${d1}`)
  await p.reload()
  await p.waitForSelector('[data-testid="list-view"]', { timeout: 20000 })
  const d2 = await p.evaluate(() => document.documentElement.getAttribute('data-density'))
  check(d2 === 'compact', '刷新后密度偏好保持（localStorage）', `data-density=${d2}`)
  await p.locator('[data-testid="density-comfy"]').click()

  // ---- ⑥ 侧板：打开 / Esc 关闭 ----
  await p.locator('[data-testid="list-search"]').fill(nameA)
  await p.waitForSelector(`[data-testid="activity-row-${idA}"]`, { timeout: 10000 })
  await p.locator(`[data-testid="activity-row-${idA}"] .activity-name`).first().click()
  await p.waitForSelector('[data-testid="side-panel"]', { timeout: 5000 })
  ok('点活动名开出右侧详情板')
  await p.locator('[data-testid="side-panel"]').press('Escape')
  await p.waitForSelector('[data-testid="side-panel"]', { state: 'detached', timeout: 5000 })
  ok('Esc 关闭侧板')

  // ---- ⑦ 零横向溢出 ----
  const of = await p.evaluate(() => document.body.scrollWidth - window.innerWidth)
  check(of <= 4, `工作台不把 body 撑出横滚（${of}px，宽表在自己的容器里滚）`, `横向溢出 ${of}px`)
} catch (e) {
  no('异常: ' + e.message)
  await p.screenshot({ path: '/tmp/bench-fail.png' }).catch(() => {})
}

console.log(`\n结果: pass=${pass} fail=${fail}`)
await b.close()
process.exit(fail ? 1 : 0)
