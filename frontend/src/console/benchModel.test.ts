import { describe, it, expect } from 'vitest'
import {
  mergeRows, deriveState, versionForTarget, filterRows, sortRows, nextSortDir,
  pruneSelection, summarize, parseTime, type BenchRow,
} from './benchModel'
import type { ActivityListRow } from '@/shared/types'

const DAY = 86_400_000
const NOW = Date.parse('2026-11-06T15:44:00+08:00')

function row(p: Partial<ActivityListRow> & { activityId: string; version: number }): ActivityListRow {
  return {
    activityName: '活动-' + p.activityId,
    bizLine: 'mall',
    activityType: 1,
    activityStatus: 0,
    activityStartTime: new Date(NOW - DAY).toISOString(),
    activityEndTime: new Date(NOW + DAY).toISOString(),
    inventory: null,
    ...p,
  } as ActivityListRow
}

describe('parseTime', () => {
  it('吃 ISO 字符串与 epoch 毫秒，坏值回 null 而不是 NaN', () => {
    expect(parseTime('2026-11-06T07:44:00Z')).toBe(Date.parse('2026-11-06T07:44:00Z'))
    expect(parseTime(NOW)).toBe(NOW)
    expect(parseTime(null)).toBeNull()
    expect(parseTime('')).toBeNull()
    expect(parseTime('不是时间')).toBeNull()
  })
})

describe('deriveState —— 时间窗判据与决策侧同源（两端闭区间）', () => {
  it('未上线一律是草稿，与时间窗无关', () => {
    expect(deriveState(0, NOW - DAY, NOW + DAY, NOW)).toBe('draft')
  })
  it('已下线一律是下线，即使还在窗内', () => {
    expect(deriveState(2, NOW - DAY, NOW + DAY, NOW)).toBe('offline')
  })
  it('上线 + 窗内 = 生效中；窗前 = 预热中；窗后 = 已过期', () => {
    expect(deriveState(1, NOW - DAY, NOW + DAY, NOW)).toBe('live')
    expect(deriveState(1, NOW + DAY, NOW + 2 * DAY, NOW)).toBe('warmup')
    expect(deriveState(1, NOW - 2 * DAY, NOW - DAY, NOW)).toBe('expired')
  })
  it('边界是闭区间：恰好等于起点或终点都算生效中', () => {
    expect(deriveState(1, NOW, NOW + DAY, NOW)).toBe('live')
    expect(deriveState(1, NOW - DAY, NOW, NOW)).toBe('live')
  })
})

describe('mergeRows —— 一行一活动', () => {
  it('线上 v1 与草稿 v2 并存时只出一行，主版本是**正在服务的 v1**', () => {
    const merged = mergeRows([
      row({ activityId: 'ACT1', version: 2, activityStatus: 0 }),
      row({ activityId: 'ACT1', version: 1, activityStatus: 1 }),
    ], NOW)

    expect(merged).toHaveLength(1)
    expect(merged[0].version).toBe(1)
    expect(merged[0].latestVersion).toBe(2)
    expect(merged[0].draftVersion).toBe(2)
    expect(merged[0].state).toBe('live')
  })

  it('没有线上版时取最高版，且 draftVersion 为 null（没有"草稿压在线上版之上"这回事）', () => {
    const merged = mergeRows([
      row({ activityId: 'ACT2', version: 1, activityStatus: 0 }),
      row({ activityId: 'ACT2', version: 3, activityStatus: 0 }),
    ], NOW)

    expect(merged[0].version).toBe(3)
    expect(merged[0].latestVersion).toBe(3)
    expect(merged[0].draftVersion).toBeNull()
  })

  it('异常数据有多个 ONLINE 版时取其中最高的那一版', () => {
    const merged = mergeRows([
      row({ activityId: 'ACT3', version: 1, activityStatus: 1 }),
      row({ activityId: 'ACT3', version: 2, activityStatus: 1 }),
    ], NOW)
    expect(merged[0].version).toBe(2)
  })

  it('不同活动不会被并到一起', () => {
    expect(mergeRows([
      row({ activityId: 'A', version: 1 }),
      row({ activityId: 'B', version: 1 }),
    ], NOW)).toHaveLength(2)
  })
})

describe('versionForTarget —— 批量动作打到哪一版', () => {
  const r: BenchRow = mergeRows([
    row({ activityId: 'ACT1', version: 2, activityStatus: 0 }),
    row({ activityId: 'ACT1', version: 1, activityStatus: 1 }),
  ], NOW)[0]

  it('下线打到正在服务的 v1 —— 打到草稿 v2 等于线上继续发钱', () => {
    expect(versionForTarget(r, 2)).toBe(1)
  })
  it('上线（发布）打到最高版 v2 —— 发布的就是最新草稿', () => {
    expect(versionForTarget(r, 1)).toBe(2)
  })
})

describe('filterRows', () => {
  const rows = mergeRows([
    row({ activityId: 'ACT1', version: 1, activityStatus: 1, activityName: '双十一预热' }),
    row({ activityId: 'ACT2', version: 1, activityStatus: 2, activityName: '老客召回' }),
  ], NOW)

  it('关键词匹配名称 / ID / 业务线，大小写不敏感', () => {
    expect(filterRows(rows, '双十一', '')).toHaveLength(1)
    expect(filterRows(rows, 'act2', '')).toHaveLength(1)
    expect(filterRows(rows, 'MALL', '')).toHaveLength(2)
  })
  it('状态筛选按存储态过滤', () => {
    expect(filterRows(rows, '', 1)).toHaveLength(1)
    expect(filterRows(rows, '', 2)).toHaveLength(1)
  })
})

describe('排序', () => {
  it('三态循环：升 → 降 → 取消', () => {
    expect(nextSortDir(null)).toBe('asc')
    expect(nextSortDir('asc')).toBe('desc')
    expect(nextSortDir('desc')).toBeNull()
  })
  it('dir=null 原样返回，不重排', () => {
    const rows = mergeRows([row({ activityId: 'B', version: 1 }), row({ activityId: 'A', version: 1 })], NOW)
    expect(sortRows(rows, 'name', null).map((r) => r.activityId)).toEqual(['B', 'A'])
  })
  it('按生效窗排序时，起始时间缺失的行排在最后而不是被当成 1970 顶到最前', () => {
    const rows = mergeRows([
      row({ activityId: 'HAS', version: 1, activityStartTime: new Date(NOW).toISOString() }),
      row({ activityId: 'NONE', version: 1, activityStartTime: null }),
    ], NOW)
    expect(sortRows(rows, 'window', 'asc').map((r) => r.activityId)).toEqual(['HAS', 'NONE'])
  })
})

describe('pruneSelection —— 筛选变化后只保留看得见的', () => {
  it('看不见的行必须从选中集里掉出去', () => {
    const visible = mergeRows([row({ activityId: 'A', version: 1 })], NOW)
    const next = pruneSelection(new Set(['A', 'B']), visible)
    expect([...next]).toEqual(['A'])
  })
  it('全部不可见时收敛成空集，而不是保留旧集合', () => {
    expect(pruneSelection(new Set(['A']), []).size).toBe(0)
  })
})

describe('summarize —— 唯一有真实数据源的那组指标', () => {
  it('只数生效中的；7 日内到期是生效中的子集', () => {
    const rows = mergeRows([
      row({ activityId: 'L1', version: 1, activityStatus: 1, activityEndTime: new Date(NOW + 3 * DAY).toISOString() }),
      row({ activityId: 'L2', version: 1, activityStatus: 1, activityEndTime: new Date(NOW + 30 * DAY).toISOString() }),
      row({ activityId: 'OFF', version: 1, activityStatus: 2 }),
    ], NOW)
    expect(summarize(rows, NOW)).toEqual({ total: 3, live: 2, endingSoon: 1 })
  })
  it('已过期的活动不计入生效中，也不计入即将到期', () => {
    const rows = mergeRows([
      row({
        activityId: 'E', version: 1, activityStatus: 1,
        activityStartTime: new Date(NOW - 2 * DAY).toISOString(),
        activityEndTime: new Date(NOW - DAY).toISOString(),
      }),
    ], NOW)
    expect(summarize(rows, NOW)).toEqual({ total: 1, live: 0, endingSoon: 0 })
  })
})
