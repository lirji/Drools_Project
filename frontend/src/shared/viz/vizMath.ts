/**
 * 量具与图表的**纯数学**（PR-3）。
 *
 * <p>刻意与组件分离：这些函数是本批次里唯一会算错、且算错了肉眼看不出来的部分。
 * 设计评审否掉原方案时点名的就是这里——proof 里把「4 个月」画成 36% 宽、「2 天」画成 16% 宽，
 * 同一根轴上二者不可能同时正确，而截图看着挺像回事。所以轴映射必须能被单测钉死。
 */

/** 甘特轴域：今天往前 60 天、往后 30 天。定死才能让所有行共享同一坐标系。 */
export const AXIS_PAST_DAYS = 60
export const AXIS_FUTURE_DAYS = 30
export const AXIS_SPAN_DAYS = AXIS_PAST_DAYS + AXIS_FUTURE_DAYS

const DAY = 86_400_000

export interface WindowGeometry {
  /** 条的左边界，0–100（%） */
  left: number
  /** 条的宽度，0–100（%）。**不含最小可视宽**——那是 CSS 的 min-width 该管的事 */
  width: number
  /** 起点早于轴域左界：需要画左截断箭头 */
  cutLeft: boolean
  /** 终点晚于轴域右界：需要画右截断箭头 */
  cutRight: boolean
  /** 当前时刻游标位置（%）。同一 now 下**所有行必须相同**，这是跨行可比的前提 */
  nowPct: number
  /** 完全落在轴域之外（整段不可见） */
  offAxis: boolean
}

/**
 * 把一个 [start, end] 时间窗映射到共享轴上。
 *
 * @param start 生效开始
 * @param end   生效结束
 * @param now   当前时刻（轴域以它为中心偏置）
 */
export function mapWindow(start: Date | number, end: Date | number, now: Date | number): WindowGeometry {
  const t0 = +now - AXIS_PAST_DAYS * DAY
  const t1 = +now + AXIS_FUTURE_DAYS * DAY
  const span = t1 - t0

  const s = +start
  const e = +end

  const rawLeft = ((s - t0) / span) * 100
  const rawRight = ((e - t0) / span) * 100

  const offAxis = rawRight < 0 || rawLeft > 100

  const left = clamp(rawLeft)
  const right = clamp(rawRight)

  return {
    left,
    width: Math.max(0, right - left),
    cutLeft: rawLeft < 0,
    cutRight: rawRight > 100,
    nowPct: (( +now - t0) / span) * 100,
    offAxis,
  }
}

function clamp(v: number): number {
  return Math.min(100, Math.max(0, v))
}

/** 量具状态：液面是否越过临界线。颜色之外还要给文字，故把判定独立出来。 */
export function gaugeState(percent: number, threshold = 80): { over: boolean; label: string } {
  const p = Math.max(0, Math.min(100, percent))
  const over = p > threshold
  return { over, label: over ? `${round1(p)}% 越线` : `${round1(p)}%` }
}

function round1(v: number): number {
  return Math.round(v * 10) / 10
}

/**
 * sparkline 折线路径。viewBox 固定 100×30，靠 `preserveAspectRatio="none"` 横向拉伸。
 *
 * <p>全零或单点时返回一条基线而不是空串——**空白读起来像「没渲染出来」，虚线读起来像「确实是 0」**。
 */
export function sparklinePath(values: number[], w = 100, h = 30): string {
  if (!values.length) return `M0,${h / 2}L${w},${h / 2}`
  if (values.length === 1) return `M0,${h / 2}L${w},${h / 2}`

  const max = Math.max(...values)
  const min = Math.min(...values)
  const range = max - min

  return values
    .map((v, i) => {
      const x = (i / (values.length - 1)) * w
      // 全等值时压在中线，避免除零把点甩到边界
      const y = range === 0 ? h / 2 : h - ((v - min) / range) * h
      return `${i ? 'L' : 'M'}${round1(x)},${round1(y)}`
    })
    .join('')
}

/** 分段条的宽度分配。总和不为 100 时**不静默归一**——缺口要能被看见并播报。 */
export function segmentWidths(parts: number[]): { widths: number[]; gap: number } {
  const total = parts.reduce((a, b) => a + b, 0)
  return { widths: parts.map((p) => Math.max(0, p)), gap: round1(100 - total) }
}
