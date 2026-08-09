import { describe, it, expect } from 'vitest'
import { mapWindow, gaugeState, sparklinePath, segmentWidths, AXIS_PAST_DAYS, AXIS_SPAN_DAYS } from './vizMath'

const DAY = 86_400_000
const NOW = new Date('2026-11-06T00:00:00Z')
const d = (offsetDays: number) => new Date(+NOW + offsetDays * DAY)

/**
 * 甘特轴映射是本批次里唯一「算错了肉眼看不出来」的部分——
 * 设计评审否掉原方案时点名的就是它：proof 把「4 个月」画成 36% 宽、「2 天」画成 16% 宽，
 * 同一根轴上二者不可能同时正确，而截图看着挺像回事。所以这里逐条钉死。
 */
describe('mapWindow（共享时间轴）', () => {
  it('游标恒在 66.67%——所有行共享同一坐标系的前提', () => {
    const a = mapWindow(d(-10), d(10), NOW)
    const b = mapWindow(d(-50), d(-40), NOW)
    expect(a.nowPct).toBeCloseTo((AXIS_PAST_DAYS / AXIS_SPAN_DAYS) * 100, 6)
    expect(a.nowPct).toBe(b.nowPct)   // 跨行必须完全相同，否则图在说谎
  })

  it('等长的窗在轴上宽度相同，与它落在哪一段无关', () => {
    const early = mapWindow(d(-50), d(-40), NOW)   // 10 天
    const late = mapWindow(d(10), d(20), NOW)      // 10 天
    expect(early.width).toBeCloseTo(late.width, 6)
    expect(early.width).toBeCloseTo((10 / AXIS_SPAN_DAYS) * 100, 6)
  })

  it('长窗与短窗的宽度比 = 天数比（原方案就是在这里崩的）', () => {
    const long = mapWindow(d(-60), d(60), NOW)   // 覆盖整个轴域
    const short = mapWindow(d(0), d(2), NOW)     // 2 天
    expect(short.width).toBeCloseTo((2 / AXIS_SPAN_DAYS) * 100, 6)
    // 2 天在 90 天轴上约 2.2%——**它就该是很窄的**，靠 CSS 的 min-width 兜可视性，不靠篡改数学
    expect(short.width).toBeLessThan(3)
    expect(long.width).toBe(100)
  })

  it('越界两端各自截断并标记', () => {
    const past = mapWindow(d(-200), d(-30), NOW)
    expect(past.cutLeft).toBe(true)
    expect(past.cutRight).toBe(false)
    expect(past.left).toBe(0)

    const future = mapWindow(d(-10), d(200), NOW)
    expect(future.cutLeft).toBe(false)
    expect(future.cutRight).toBe(true)
    expect(future.left + future.width).toBe(100)

    const both = mapWindow(d(-200), d(200), NOW)
    expect(both.cutLeft && both.cutRight).toBe(true)
    expect(both.width).toBe(100)
  })

  it('完全在轴域之外 → offAxis，不画条', () => {
    expect(mapWindow(d(-200), d(-100), NOW).offAxis).toBe(true)
    expect(mapWindow(d(100), d(200), NOW).offAxis).toBe(true)
    expect(mapWindow(d(-10), d(10), NOW).offAxis).toBe(false)
  })

  it('恰好贴住轴域边界不算越界', () => {
    const g = mapWindow(d(-AXIS_PAST_DAYS), d(30), NOW)
    expect(g.cutLeft).toBe(false)
    expect(g.left).toBe(0)
    expect(g.width).toBe(100)
  })
})

describe('gaugeState（量具临界线）', () => {
  it('越过 80% 才算越线，且文字与颜色双编码', () => {
    expect(gaugeState(63.1).over).toBe(false)
    expect(gaugeState(63.1).label).toBe('63.1%')
    expect(gaugeState(91.4).over).toBe(true)
    expect(gaugeState(91.4).label).toBe('91.4% 越线')
  })

  it('恰等阈值不算越线（阈值是"超过"不是"达到"）', () => {
    expect(gaugeState(80).over).toBe(false)
  })

  it('越界输入被夹住，不产生负宽或超 100 的液面', () => {
    expect(gaugeState(-5).label).toBe('0%')
    expect(gaugeState(150).over).toBe(true)
    expect(gaugeState(150).label).toBe('100% 越线')
  })
})

describe('sparklinePath', () => {
  it('空数据画基线而不是空串——空白读起来像"没渲染出来"', () => {
    expect(sparklinePath([])).toBe('M0,15L100,15')
    expect(sparklinePath([7])).toBe('M0,15L100,15')
  })

  it('全等值压在中线，不因除零把点甩到边界', () => {
    const p = sparklinePath([5, 5, 5])
    expect(p).toBe('M0,15L50,15L100,15')
  })

  it('最大值贴顶、最小值贴底', () => {
    const p = sparklinePath([0, 10])
    expect(p).toBe('M0,30L100,0')
  })
})

describe('segmentWidths（桶分配条）', () => {
  it('总和不足 100 时暴露缺口，不静默归一', () => {
    expect(segmentWidths([50, 30, 16]).gap).toBe(4)
    expect(segmentWidths([50, 25, 25]).gap).toBe(0)
  })

  it('负值被夹到 0', () => {
    expect(segmentWidths([-10, 50]).widths).toEqual([0, 50])
  })
})
