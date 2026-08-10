// PR-6 玩法模板屏的浏览器回归。
//
// 单测能证明「目录常量写对了」「点击 emit 了正确的路由」，证明不了这条**跨屏链路**：
// 从模板屏点「用它新建」→ 带着 query 落到编辑器 → initialize() 把 preset 摊进 Draft → 表单真的填好了。
// 这中间任何一环断掉，表现都是「跳过去了，但表单是空的」，而单测各自都还是绿的。
//
//   用法：BASE=http://localhost:8095 node e2e/e2e-playbooks.mjs
import { chromium } from 'playwright'

const BASE = process.env.BASE || 'http://localhost:8095'
const TENANT = process.env.TENANT || 'acme'

const b = await chromium.launch()
const p = await b.newPage({ viewport: { width: 1440, height: 900 } })
let pass = 0, fail = 0
const ok = (m) => { console.log('  ✅ ' + m); pass++ }
const no = (m) => { console.log('  ❌ ' + m); fail++ }
const check = (cond, msg, detail = '') => { if (cond) ok(msg); else no(msg + (detail ? ' → ' + detail : '')) }
const brief = (s) => String(s).replace(/\s+/g, ' ').slice(0, 160)

try {
  await p.goto(`${BASE}/ui/console`)
  await p.waitForSelector('[data-testid="tenant-bar"]', { timeout: 20000 })
  await p.locator(`[data-testid="tenant-chip-${TENANT}"]`).click()

  // ---- ① 侧栏入口 → 模板屏 ----
  await p.locator('[data-testid="tab-playbooks"]').click()
  await p.waitForSelector('[data-testid="playbooks-view"]', { timeout: 10000 })
  ok('侧栏「玩法模板」入口可达')

  const cards = await p.locator('[data-testid^="playbook-card-"]').count()
  check(cards >= 12, `模板卡渲染出 ${cards} 张`, `只有 ${cards} 张`)

  // ---- ② 诚实性：说明卡 + 不可用玩法写明缺什么、且不给「用它新建」 ----
  const note = await p.locator('[data-testid="playbooks-note"]').innerText()
  check(/不新增后端能力/.test(note), '说明卡讲清「这些模板不新增后端能力」', brief(note))

  // 「第二件半价」原本标灰（决策入口没有逐行单价）。2026-08 决策入口补了 lines + 新增
  // NTH_ZHE 形态后已解锁——断言随之翻面：不该再标灰，且必须给「用它新建」。
  const nthCard = await p.locator('[data-testid="playbook-card-second-half"]').innerText()
  check(!/暂不支持|缺什么/.test(nthCard), '「第二件半价」已不再标灰', brief(nthCard))
  const nthUse = await p.locator('[data-testid="playbook-use-second-half"]').count()
  check(nthUse === 1, '「第二件半价」可以「用它新建」', `按钮数 ${nthUse}`)

  // 标灰机制本身仍要活着——将来再出现做不到的玩法时还得靠它。
  // 现在没有 blocked 玩法，故只断言「若存在 blocked 卡，则必须写明缺什么且不给按钮」。
  const blockedCards = await p.locator('[data-testid^="playbook-card-"]').evaluateAll(
    (els) => els.filter((e) => /暂不支持/.test(e.innerText)).map((e) => e.getAttribute('data-testid')))
  let blockedOk = true
  for (const testid of blockedCards) {
    const text = await p.locator(`[data-testid="${testid}"]`).innerText()
    const id = testid.replace('playbook-card-', '')
    const useBtn = await p.locator(`[data-testid="playbook-use-${id}"]`).count()
    if (!/缺什么/.test(text) || useBtn !== 0) blockedOk = false
  }
  check(blockedOk, `标灰机制完好（当前 ${blockedCards.length} 个不可用玩法）`, '有标灰卡未写明缺什么或仍给了按钮')

  // 折扣券自 2026-08 引擎加了按比例形态后已可用——卡上不该再写「不支持」
  const discountCard = await p.locator('[data-testid="playbook-card-discount"]').innerText()
  check(!/暂不支持|缺什么/.test(discountCard), '「折扣券」已不再标灰', brief(discountCard))
  const discountUse = await p.locator('[data-testid="playbook-use-discount"]').count()
  check(discountUse === 1, '「折扣券」可以「用它新建」', `按钮数 ${discountUse}`)

  // ---- ③ 筛选 ----
  await p.locator('[data-testid="playbook-filter-gift"]').click()
  const afterFilter = await p.locator('[data-testid^="playbook-card-"]').count()
  const ladderGone = await p.locator('[data-testid="playbook-card-ladder"]').count()
  check(afterFilter >= 1 && ladderGone === 0, `筛选「赠品类」后只剩 ${afterFilter} 张`, `阶梯卡仍在`)
  await p.locator('[data-testid="playbook-filter-all"]').click()

  // ---- ④ 跨屏链路：用它新建 → 编辑器真的被预填 ----
  await p.locator('[data-testid="playbook-use-ladder"]').click()
  await p.waitForSelector('[data-testid="editor-view"]', { timeout: 10000 })
  await p.waitForSelector('[data-testid="playbook-applied"]', { timeout: 10000 })
  const banner = await p.locator('[data-testid="playbook-applied"]').innerText()
  check(/起点/.test(banner) && /阶梯满减/.test(banner) && /都可以改/.test(banner),
    '编辑器提示「起点为阶梯满减模板，每一项都可以改」', brief(banner))

  const plain = await p.locator('[data-testid="tier-plain"]').innerText()
  check(/300/.test(plain) && /600/.test(plain) && /1,?000/.test(plain),
    '阶梯档位真的填进去了（人话预览已在讲三档）', brief(plain))

  const url = p.url()
  check(/playbook=ladder/.test(url), 'URL 带得住模板参数（可分享/可刷新）', url)

  // ---- ④b 折扣券端到端：预填 → 真的能保存（写平面强制封顶，能保存就说明封顶带过去了）----
  await p.goto(`${BASE}/ui/console/playbooks`)
  await p.waitForSelector('[data-testid="playbooks-view"]', { timeout: 10000 })
  await p.locator('[data-testid="playbook-use-discount"]').click()
  await p.waitForSelector('[data-testid="form-zhe"]', { timeout: 10000 })
  const zheVal = await p.locator('[data-testid="form-zhe"]').inputValue()
  const capVal = await p.locator('[data-testid="form-max-discount"]').inputValue()
  check(zheVal === '8' && capVal === '50', `折扣模板预填折数 ${zheVal} / 封顶 ${capVal}`, `${zheVal} / ${capVal}`)
  const ratioPlain = await p.locator('[data-testid="ratio-plain"]').innerText()
  check(/最多减 50 元/.test(ratioPlain) && /250/.test(ratioPlain),
    '人话预览讲清封顶额与到顶门槛（8 折封顶 50 → 满 250 元到顶）', brief(ratioPlain))

  await p.locator('[data-testid="form-name"]').fill(`E2E折扣券-${Date.now()}`)
  await p.locator('[data-testid="spu-row-input"]').first().fill(String(930000 + Math.floor(Math.random() * 9000)))
  await p.locator('[data-testid="submit"]').click()
  await p.waitForSelector('[data-testid="save-success"]', { timeout: 15000 })
  ok('折扣券可保存 —— 写平面强制封顶，能存下来就说明封顶真的提交了')

  // ---- ⑤ 「随机金额」是一等权益形态：独立 chip，且选中后换成区间输入 ----
  await p.goto(`${BASE}/ui/console/activities/new?playbook=flat`)
  await p.waitForSelector('[data-testid="mode-random"]', { timeout: 10000 })
  const legacyTakeType = await p.locator('[data-testid="form-take-type"]').count()
  check(legacyTakeType === 0, '发放方式下拉已撤掉，不再形成随机形态的第二权威', `旧下拉数 ${legacyTakeType}`)

  await p.locator('[data-testid="mode-random"]').click()
  await p.waitForSelector('[data-testid="form-range-min"]', { timeout: 10000 })
  const hasRange = await p.locator('[data-testid="form-range-max"]').count()
  const hasFixed = await p.locator('[data-testid="form-amount"]').count()
  check(hasRange === 1 && hasFixed === 0,
    '选中随机后固定金额输入让位给区间两端', `range=${hasRange} fixed=${hasFixed}`)
  // 必须写明它不是真抽奖——否则运营会以为多刷几次能拿到不同金额
  const takeHint = await p.locator('[data-testid="form-range-min"]').locator('xpath=../..').innerText()
  check(/确定性随机/.test(takeHint), '写明是确定性随机（刷新不变价）', brief(takeHint))

  // ---- ⑥ 零横向溢出 ----
  await p.goto(`${BASE}/ui/console/playbooks`)
  await p.waitForSelector('[data-testid="playbooks-view"]', { timeout: 10000 })
  const of = await p.evaluate(() => document.body.scrollWidth - window.innerWidth)
  check(of <= 4, `模板屏不把 body 撑出横滚（${of}px）`, `横向溢出 ${of}px`)

  // 手机档也过一遍：卡片网格是 auto-fill，最容易在窄屏撑破
  await p.setViewportSize({ width: 390, height: 844 })
  await p.reload()
  await p.waitForSelector('[data-testid="playbooks-view"]', { timeout: 10000 })
  const ofPhone = await p.evaluate(() => document.body.scrollWidth - window.innerWidth)
  check(ofPhone <= 4, `模板屏 390px 下零横向溢出（${ofPhone}px）`, `横向溢出 ${ofPhone}px`)
} catch (e) {
  no('异常: ' + e.message)
  await p.screenshot({ path: '/tmp/playbooks-fail.png' }).catch(() => {})
}

console.log(`\n结果: pass=${pass} fail=${fail}`)
await b.close()
process.exit(fail ? 1 : 0)
