// 视觉换代红线守卫（视觉换代 0809 · 步骤 9）。
//
// 换代计划里 A-6/A-8/A-9/A-12 四条验收标准原本只写了「用 Playwright 量」，
// 却没有任何一步产出这些断言——那样最后只会变成"人工目测过了"。这个脚本把它们固化下来。
//
// 它守的**不是好看**，是几条会静默退化、且退化后没人会发现的工程红线：
//   1. 手机端 ListView 工具条被 flex-basis 撑高（缺陷 F3 的回归护栏）
//   2. 小屏/触控下 backdrop-filter 必须关闭（低端安卓与微信 WebView 的掉帧主因）
//   3. prefers-reduced-motion 下不得有循环动画在跑
//   4. 打印时必须回落白底（暗色档打印会出一张全黑纸）
//   5. 深色面上的强调色必须是主题无关的（用会翻面的 --accent-2 只有 2.9:1）
//
// 用法（header 档，编排口）：
//   DROOLS_AUTH_ENABLED=false DROOLS_DEV_DEFAULT_ENABLED=true \
//     docker compose -f deploy/docker-compose.yml up -d
//   BASE=http://localhost:8095 node frontend/e2e/e2e-visual-guard.mjs
import { chromium } from 'playwright'

const BASE = process.env.BASE || 'http://localhost:8095'
const results = []
const ok = (m) => { results.push(['PASS', m]); console.log('  ✅', m) }
const no = (m) => { results.push(['FAIL', m]); console.log('  ❌', m) }

const browser = await chromium.launch()

try {
  // ── A-6：手机端 ListView 工具条几何（缺陷 F3 回归护栏）────────────────────
  // ≤1023 的 `.search-box { flex: 1 1 240px }` 是给 row 方向写的；≤560 容器转 column 后
  // 240px 会从「宽度基准」变成「高度基准」，搜索框曾被撑成 316×240 的空盒。
  {
    const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
    const page = await ctx.newPage()
    await page.goto(`${BASE}/ui/console/activities`, { waitUntil: 'networkidle' })
    await page.waitForSelector('.search-box', { timeout: 15000 })
    const box = await page.evaluate(() => {
      const s = document.querySelector('.search-box')?.getBoundingClientRect()
      const t = document.querySelector('.toolbar')?.getBoundingClientRect()
      return { search: s ? Math.round(s.height) : -1, toolbar: t ? Math.round(t.height) : -1 }
    })
    box.search > 0 && box.search <= 56
      ? ok(`A-6 手机搜索框高度 ${box.search}px（≤56）`)
      : no(`A-6 手机搜索框高度 ${box.search}px，超出 56 —— flex-basis 又被当成高度基准了`)
    box.toolbar > 0 && box.toolbar <= 240
      ? ok(`A-6 手机工具条高度 ${box.toolbar}px（≤240）`)
      : no(`A-6 手机工具条高度 ${box.toolbar}px，超出 240`)

    // ── A-8：小屏必须关掉玻璃 + 触控命中区 ≥44px ─────────────────────────
    const glass = await page.evaluate(() => {
      const el = document.querySelector('.shell-topbar')
      const cs = el && getComputedStyle(el)
      return cs ? (cs.backdropFilter || cs.webkitBackdropFilter || 'none') : 'missing'
    })
    glass === 'none'
      ? ok('A-8 手机端顶栏 backdrop-filter 已关闭')
      : no(`A-8 手机端顶栏仍在 backdrop-filter: ${glass}`)

    const tooSmall = await page.evaluate(() => {
      const els = [...document.querySelectorAll('button, select, [role="button"], a[role="button"]')]
      return els
        .filter((e) => e.getBoundingClientRect().width > 0 && e.getBoundingClientRect().height > 0)
        .filter((e) => e.getBoundingClientRect().height < 44 - 0.5)
        .map((e) => `${e.tagName.toLowerCase()}.${e.className}`.slice(0, 60))
        .slice(0, 5)
    })
    tooSmall.length === 0
      ? ok('A-8 触控命中区全部 ≥44px')
      : no(`A-8 有 ${tooSmall.length} 个触控目标 <44px：${tooSmall.join(' / ')}`)

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    overflow <= 4 ? ok(`A-7 手机零横向溢出（${overflow}px）`) : no(`A-7 手机横向溢出 ${overflow}px`)
    await ctx.close()
  }

  // ── A-9：reduced-motion 下不得有循环动画在跑 ────────────────────────────
  // tokens.css 的全局闸是「压时长 + 强制 iteration-count:1」，不是 animation:none，
  // 所以这里查的是「还有没有 iteration-count 为 infinite 的元素」。
  {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' })
    const page = await ctx.newPage()
    await page.goto(`${BASE}/ui/home`, { waitUntil: 'networkidle' })
    await page.waitForTimeout(500)
    const infinite = await page.evaluate(() =>
      [...document.querySelectorAll('*')]
        .filter((e) => getComputedStyle(e).animationIterationCount === 'infinite')
        .map((e) => `${e.tagName.toLowerCase()}.${e.className}`.slice(0, 50))
        .slice(0, 5))
    infinite.length === 0
      ? ok('A-9 reduced-motion 下无循环动画在跑')
      : no(`A-9 reduced-motion 下仍有 ${infinite.length} 个循环动画：${infinite.join(' / ')}`)
    await ctx.close()
  }

  // ── A-12 + 深色面强调色主题无关性 ──────────────────────────────────────
  {
    for (const theme of ['dark', 'light']) {
      const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
      await ctx.addInitScript((t) => { try { localStorage.setItem('drools-theme', t) } catch { /* 隐私模式 */ } }, theme)
      const page = await ctx.newPage()
      await page.goto(`${BASE}/ui/home`, { waitUntil: 'networkidle' })
      await page.waitForSelector('.stat-value', { timeout: 15000 })

      // hero 是"永远深色"的面，压在它上面的强调色若用会翻面的 --accent-2，
      // 浅色档会拿到压暗版 #0C6B85，落在 --surface-deep 上只有 2.9:1。
      const statColor = await page.evaluate(() => getComputedStyle(document.querySelector('.stat-value')).color)
      statColor === 'rgb(34, 211, 238)'
        ? ok(`深色面强调色主题无关（${theme} 档为 ${statColor}）`)
        : no(`${theme} 档深色面强调色为 ${statColor}，应恒为 rgb(34, 211, 238)`)

      // A-12 打印：回落白底、去背景图
      await page.emulateMedia({ media: 'print' })
      const printBg = await page.evaluate(() => {
        const cs = getComputedStyle(document.body)
        return { color: cs.backgroundColor, image: cs.backgroundImage }
      })
      const white = printBg.color === 'rgb(255, 255, 255)'
      const noImage = printBg.image === 'none'
      white && noImage
        ? ok(`A-12 打印回落白底无背景图（${theme} 档）`)
        : no(`A-12 打印底色 ${printBg.color} / 背景图 ${printBg.image}（${theme} 档）`)
      await page.emulateMedia({ media: 'screen' })
      await ctx.close()
    }
  }
} catch (e) {
  no(`脚本异常：${e.message}`)
} finally {
  await browser.close()
}

const failed = results.filter(([s]) => s === 'FAIL')
console.log(`\n视觉红线守卫：${results.length - failed.length}/${results.length} 通过`)
if (failed.length) {
  console.log('失败项：')
  for (const [, m] of failed) console.log('  -', m)
  process.exit(1)
}
