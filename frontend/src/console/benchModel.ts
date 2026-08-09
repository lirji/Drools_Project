/**
 * 活动工作台的**纯逻辑**（PR-5）：行归并 / 状态派生 / 选择模型 / 动作版本选择。
 *
 * <p>与组件分开是因为这批里真正会出错、且出错了肉眼看不出来的就是它们：
 * 「同一活动被数成两个」「批量下线打到草稿」这两条都不是渲染问题，是模型问题。
 */
import type { ActivityListRow } from '@/shared/types'

/**
 * 工作台的五态。**不是**设计规范里那六态——
 * 后端 `ActivityStatus` 只有 4 个枚举值，其中 `PENDING_EFFECT(3)` 全仓无写入点，
 * 而「灰度中」在整个 activity-* 源码里不存在任何按活动的灰度比例。
 * 所以这里是 2 个存储态 + 3 个由时间窗派生的态，没有占位、没有假态。
 */
export type BenchState = 'draft' | 'warmup' | 'live' | 'expired' | 'offline'

export interface BenchRow {
  activityId: string
  activityName: string
  bizLine: string | null
  activityType: number
  /** 展示与「下线」动作用的主版本：有 ONLINE 版就是它，否则取最高版 */
  version: number
  activityStatus: number
  /** 最高版本。「上线（发布）」动作用它 */
  latestVersion: number
  /** 线上版之上还压着一版草稿时给出草稿版本号（P0-4 之后线上与草稿并存） */
  draftVersion: number | null
  start: number | null
  end: number | null
  /** 声明式库存。**没有已用量**，所以只能当数字展示，不能拿来画液面 */
  inventory: number | null
  state: BenchState
}

/** 后端 `Instant` 序列化成 ISO 字符串，但历史/测试数据里也可能是 epoch 毫秒 */
export function parseTime(v: string | number | null | undefined): number | null {
  if (v === null || v === undefined || v === '') return null
  const t = typeof v === 'number' ? v : Date.parse(v)
  return Number.isFinite(t) ? t : null
}

const ONLINE = 1
const OFFLINE = 2

/**
 * 状态派生。时间窗判据与决策侧 `DecisionDataLoader:201`
 * （`!now.isBefore(start) && !now.isAfter(end)`）**同源**——两端闭区间，
 * 所以工作台上写「生效中」的活动，就是决策此刻真的会取的那一批。
 */
export function deriveState(status: number, start: number | null, end: number | null, now: number): BenchState {
  if (status === OFFLINE) return 'offline'
  if (status !== ONLINE) return 'draft'
  if (start !== null && now < start) return 'warmup'
  if (end !== null && now > end) return 'expired'
  return 'live'
}

/**
 * 按 activityId 归并成「一行一活动」。
 *
 * <p>**必须归并**：`GET /list` 返回的是行不是活动，P0-4 之后编辑已上线活动会保留线上 v1
 * 另建草稿 v2，两行都 `isDel=0`，于是同一个活动在列表里出现两次——
 * Vue 的 `:key` 与 `data-testid="activity-row-{id}"` 同时重复，跨页选择还会把它数成两个。
 *
 * <p>主版本取「正在服务的那一版」而不是最高版：运营点「下线」想停的是正在发钱的那一版。
 */
export function mergeRows(rows: ActivityListRow[], now: number): BenchRow[] {
  const byId = new Map<string, ActivityListRow[]>()
  for (const r of rows) {
    const list = byId.get(r.activityId)
    if (list) list.push(r)
    else byId.set(r.activityId, [r])
  }

  const out: BenchRow[] = []
  for (const group of byId.values()) {
    // 防御性地取 ONLINE 里的最高版：P0-4 的指针切换保证最多一个 ONLINE，
    // 但历史数据/并发编辑可能留下多个，此时「最新的那个线上版」是唯一说得通的选择
    const online = group.filter((r) => r.activityStatus === ONLINE)
      .sort((a, b) => b.version - a.version)[0]
    const highest = group.slice().sort((a, b) => b.version - a.version)[0]
    const primary = online ?? highest
    const latestVersion = highest.version

    const start = parseTime(primary.activityStartTime)
    const end = parseTime(primary.activityEndTime)
    out.push({
      activityId: primary.activityId,
      activityName: primary.activityName,
      bizLine: primary.bizLine,
      activityType: primary.activityType,
      version: primary.version,
      activityStatus: primary.activityStatus,
      latestVersion,
      draftVersion: latestVersion > primary.version ? latestVersion : null,
      start,
      end,
      inventory: primary.inventory ?? null,
      state: deriveState(primary.activityStatus, start, end, now),
    })
  }
  return out
}

/**
 * 动作要打到哪一版。
 *   下线 → 主版本（正在服务的那一版）
 *   上线 → 最高版本（要发布的就是最新草稿；没有草稿时就是主版本自己，等于重新发布）
 */
export function versionForTarget(row: BenchRow, target: 1 | 2): number {
  return target === 1 ? row.latestVersion : row.version
}

/** 搜索 + 状态筛选。**注意**：不要把关键词回显进列表容器——
 *  e2e 的跨租户隔离断言读的是 `list-view` 的 innerText，回显会让它把自己的关键词当成"泄漏的活动名"。 */
export function filterRows(rows: BenchRow[], keyword: string, status: number | ''): BenchRow[] {
  const kw = keyword.trim().toLocaleLowerCase()
  return rows.filter((r) => {
    if (status !== '' && r.activityStatus !== status) return false
    if (kw && !`${r.activityName} ${r.activityId} ${r.bizLine || ''}`.toLocaleLowerCase().includes(kw)) return false
    return true
  })
}

export type SortKey = 'name' | 'window' | 'status' | 'version'
export type SortDir = 'asc' | 'desc' | null

/** 表头三态排序：升 → 降 → 取消。取消回到后端给的顺序（按 modifiedStime 倒序） */
export function nextSortDir(current: SortDir): SortDir {
  return current === null ? 'asc' : current === 'asc' ? 'desc' : null
}

const STATE_ORDER: Record<BenchState, number> = { live: 0, warmup: 1, draft: 2, expired: 3, offline: 4 }

export function sortRows(rows: BenchRow[], key: SortKey, dir: SortDir): BenchRow[] {
  if (dir === null) return rows
  const sign = dir === 'asc' ? 1 : -1
  return rows.slice().sort((a, b) => sign * cmp(a, b, key))
}

function cmp(a: BenchRow, b: BenchRow, key: SortKey): number {
  switch (key) {
    case 'name': return a.activityName.localeCompare(b.activityName, 'zh-Hans-CN')
    // 按开始时间排，缺失的排在最后（缺失不该被当成 1970 年顶到最前）
    case 'window': return nullLast(a.start, b.start)
    case 'status': return STATE_ORDER[a.state] - STATE_ORDER[b.state]
    case 'version': return a.latestVersion - b.latestVersion
  }
}

function nullLast(a: number | null, b: number | null): number {
  if (a === null && b === null) return 0
  if (a === null) return 1
  if (b === null) return -1
  return a - b
}

/**
 * 筛选变化后收敛选中集：**只保留仍然可见的行**。
 *
 * <p>否则会出现「勾了 3 个 → 改筛选 → 点批量下线，把屏幕上根本看不到的活动也下了」。
 * 能看到什么就只能操作什么，是批量操作唯一安全的心智模型。
 */
export function pruneSelection(selected: Set<string>, visible: BenchRow[]): Set<string> {
  const ids = new Set(visible.map((r) => r.activityId))
  const next = new Set<string>()
  for (const id of selected) if (ids.has(id)) next.add(id)
  return next
}

/** 生效中 / 全部 / 7 日内到期 —— 工作台唯一有真实数据源的那组指标 */
export function summarize(rows: BenchRow[], now: number): { total: number; live: number; endingSoon: number } {
  const WEEK = 7 * 86_400_000
  let live = 0
  let endingSoon = 0
  for (const r of rows) {
    if (r.state === 'live') {
      live++
      if (r.end !== null && r.end - now <= WEEK) endingSoon++
    }
  }
  return { total: rows.length, live, endingSoon }
}
