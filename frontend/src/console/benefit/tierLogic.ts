import type { LadderRow } from '../logic'

/**
 * 阶梯档位的**校验与人话化**（PR-4）。
 *
 * <p>这是把「schema 驱动的动态表单」从 key-value 泥潭里捞出来的关键：
 * 档位的**顺序关系、间距大小、覆盖是否连续**这三件事，原来要在 N 行输入框里心算。
 * 心算的结果是——重叠区间没人发现（两个档位争抢同一笔订单，命中哪个取决于实现细节），
 * 断档没人发现（600–1000 之间的订单一分钱优惠都没有，运营以为配全了）。
 *
 * <p>纯函数放这里，是因为这两类缺陷**在界面上肉眼可查、在数据里却极易漏**，必须单测钉死。
 */

export interface Tier {
  /** 门槛下界（含） */
  min: number
  /** 上界（不含）。null = 无上限 */
  max: number | null
  /** 减免金额 */
  reward: number
}

export interface Overlap { from: number; to: number; count: number }
export interface Gap { from: number; to: number }

export interface TierIssues {
  overlaps: Overlap[]
  gaps: Gap[]
  /** 给人看的一句话；无问题时为空串 */
  message: string
}

/** 归一：丢掉没填奖励的行、数值化、按下界排序。渲染与校验都以它为准。 */
export function normalizeTiers(rows: LadderRow[]): Tier[] {
  return rows
    .filter((r) => r.reward !== '' && r.reward != null)
    .map((r) => ({
      min: r.min === '' || r.min == null ? 0 : Number(r.min),
      max: r.max === '' || r.max == null ? null : Number(r.max),
      reward: Number(r.reward),
    }))
    .filter((t) => Number.isFinite(t.min) && Number.isFinite(t.reward))
    .sort((a, b) => a.min - b.min)
}

/**
 * 找出重叠与断档。
 *
 * <p>区间语义是 <b>[min, max)</b>——与后端 `BenefitEvaluator.tierOf` 一致（下界闭、上界开）。
 * 所以「上一档 max == 下一档 min」是**恰好衔接，不是重叠也不是断档**，这条最容易写反。
 */
export function validateTiers(tiers: Tier[]): TierIssues {
  const overlaps: Overlap[] = []
  const gaps: Gap[] = []

  for (let i = 0; i < tiers.length - 1; i++) {
    const cur = tiers[i]
    const next = tiers[i + 1]
    const curEnd = cur.max

    if (curEnd === null) {
      // 无上限档后面还有档 → 后面那些永远够不到
      overlaps.push({ from: next.min, to: next.max ?? next.min, count: 2 })
      continue
    }
    if (curEnd > next.min) {
      overlaps.push({ from: next.min, to: curEnd, count: 2 })
    } else if (curEnd < next.min) {
      gaps.push({ from: curEnd, to: next.min })
    }
  }

  const parts: string[] = []
  for (const o of overlaps) parts.push(`${fmt(o.from)}–${fmt(o.to)} 区间有 ${o.count} 个档位争抢`)
  for (const g of gaps) parts.push(`${fmt(g.from)}–${fmt(g.to)} 之间无优惠`)

  return { overlaps, gaps, message: parts.join('；') }
}

/**
 * 人话预览——**本屏成败的分水岭**。
 *
 * <p>schema 驱动的动态表单极易退化成一排看不懂的 key-value：运营填完不知道自己配出了什么，
 * 于是要么不敢上线、要么上线后才发现配错。实时把参数翻译成一句人话，是让运营敢按下发布的唯一手段。
 *
 * @param strategyLabel 合并策略的人话（如「取最高档」）
 * @param limitLabel    限次的人话（如「每人每天 1 次」）
 */
export function plainLanguage(tiers: Tier[], strategyLabel = '取最高档', limitLabel = ''): string {
  if (!tiers.length) return '尚未配置档位——当前不会产生任何优惠。'
  const clauses = tiers.map((t) => `订单满 ${fmt(t.min)} 元减 ${fmt(t.reward)} 元`)
  let out = clauses.join('；') + '。'
  if (strategyLabel) out += `${strategyLabel}。`
  if (limitLabel) out += limitLabel + '。'
  return out
}

/** 千分位，整数不带小数点。金额在预览里要好读，不是要精确到分。 */
function fmt(v: number): string {
  return Number.isInteger(v) ? v.toLocaleString('zh-CN') : String(v)
}
