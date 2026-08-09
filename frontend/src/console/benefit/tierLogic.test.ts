import { describe, it, expect } from 'vitest'
import { normalizeTiers, validateTiers, plainLanguage } from './tierLogic'
import type { LadderRow } from '../logic'

const rows = (...r: [number | string, number | string, number | string][]): LadderRow[] =>
  r.map(([min, max, reward]) => ({ min, max, reward }))

describe('normalizeTiers', () => {
  it('丢掉没填奖励的行、数值化、按下界排序', () => {
    const t = normalizeTiers(rows([200, '', 25], [0, 100, 5], [100, 200, ''] as never, [100, 200, 12]))
    expect(t.map((x) => x.min)).toEqual([0, 100, 200])
    expect(t[2].max).toBeNull()
  })
})

describe('validateTiers（区间语义 [min, max)）', () => {
  it('恰好衔接不算重叠也不算断档——这条最容易写反', () => {
    const issues = validateTiers(normalizeTiers(rows([0, 100, 5], [100, 200, 12], [200, '', 25])))
    expect(issues.overlaps).toHaveLength(0)
    expect(issues.gaps).toHaveLength(0)
    expect(issues.message).toBe('')
  })

  it('重叠被识别并给出人话', () => {
    const issues = validateTiers(normalizeTiers(rows([0, 300, 5], [280, 600, 12])))
    expect(issues.overlaps).toEqual([{ from: 280, to: 300, count: 2 }])
    expect(issues.message).toContain('280–300 区间有 2 个档位争抢')
  })

  it('断档被识别并给出人话', () => {
    const issues = validateTiers(normalizeTiers(rows([0, 600, 5], [1000, '', 25])))
    expect(issues.gaps).toEqual([{ from: 600, to: 1000 }])
    expect(issues.message).toContain('600–1,000 之间无优惠')
  })

  it('无上限档后面还有档 → 后面那些永远够不到，算重叠', () => {
    const issues = validateTiers(normalizeTiers(rows([0, '', 5], [500, 900, 12])))
    expect(issues.overlaps).toHaveLength(1)
  })

  it('重叠与断档可同时存在', () => {
    const issues = validateTiers(normalizeTiers(rows([0, 300, 5], [280, 500, 12], [900, '', 25])))
    expect(issues.overlaps).toHaveLength(1)
    expect(issues.gaps).toHaveLength(1)
    expect(issues.message).toContain('；')
  })

  it('单档与空档位都不报问题', () => {
    expect(validateTiers(normalizeTiers(rows([0, '', 5]))).message).toBe('')
    expect(validateTiers([]).message).toBe('')
  })
})

describe('plainLanguage（本屏成败的分水岭）', () => {
  it('把参数翻译成运营看得懂的一句话', () => {
    const t = normalizeTiers(rows([300, 600, 50], [600, 1000, 120], [1000, '', 220]))
    const s = plainLanguage(t, '取最高档，不与其它满减叠加', '每人每天 1 次')
    expect(s).toContain('订单满 300 元减 50 元')
    expect(s).toContain('订单满 1,000 元减 220 元')   // 千分位
    expect(s).toContain('取最高档')
    expect(s).toContain('每人每天 1 次')
  })

  it('没配档位时明确说"不会产生任何优惠"，而不是给空串', () => {
    expect(plainLanguage([])).toContain('不会产生任何优惠')
  })
})
