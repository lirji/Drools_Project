// 地域选择器的纯逻辑层。**不依赖任何组件与 EditorView 概念**，可单测、可被条件树 / 验证页复用。
// 与 benefit/tierLogic.ts 同位：复杂判断留在这里，组件只做渲染与事件。
import type { District } from '@/shared/types'

/** `activity_manage.district_ids` 的列宽（varchar(1024)）。后端 validateCommon 也拿这个数做前置校验。 */
export const DISTRICT_IDS_MAX_LEN = 1024
/** 6 位码 + 逗号 = 7 字符 → 最多 146 个。超一个就是 1028 > 1024，落库会失败。 */
export const MAX_DISTRICTS = Math.floor((DISTRICT_IDS_MAX_LEN + 1) / 7)

export interface DistrictIndex {
  byCode: Map<string, District>
  childrenOf: Map<string, District[]>
  roots: District[]
}

/**
 * CSV → 代码数组。**保持输入顺序、不排序**——「打开编辑器不做任何修改直接保存」必须原样出去，
 * 擅自排序会让这次保存产生一个凭空的 diff。
 */
export function parseCodes(csv: string | null | undefined): string[] {
  if (!csv) return []
  const out: string[] = []
  for (const raw of csv.split(',')) {
    const t = raw.trim()
    if (t && !out.includes(t)) out.push(t)
  }
  return out
}

export function toCsv(codes: string[]): string {
  return codes.join(',')
}

export function buildIndex(list: District[] | null): DistrictIndex {
  const byCode = new Map<string, District>()
  const childrenOf = new Map<string, District[]>()
  const roots: District[] = []
  for (const d of list || []) {
    byCode.set(d.code, d)
    if (d.parent) {
      const arr = childrenOf.get(d.parent)
      if (arr) arr.push(d)
      else childrenOf.set(d.parent, [d])
    } else {
      roots.push(d)
    }
  }
  return { byCode, childrenOf, roots }
}

/** 「广东省/深圳市/南山区」。字典里查不到就回退成裸码——**绝不返回空串**，否则界面上那一项会变成一个看不见的东西。 */
export function pathOf(index: DistrictIndex, code: string): string {
  const seg: string[] = []
  let cur = index.byCode.get(code)
  let guard = 0
  while (cur && guard++ < 8) {
    seg.unshift(cur.name)
    cur = cur.parent ? index.byCode.get(cur.parent) : undefined
  }
  return seg.length ? seg.join('/') : code
}

/** 这个码在不在字典里。不在 = 已撤销或脏数据（如 2025-11 撤销的 500105 江北区）。 */
export function isKnown(index: DistrictIndex, code: string): boolean {
  return index.byCode.has(code)
}

/** 显示名：字典里有就用简称，没有就用裸码。 */
export function labelOf(index: DistrictIndex, code: string): string {
  return index.byCode.get(code)?.shortName || code
}

/**
 * 搜索。匹配全称 / 简称 / 全拼 / 首字母 / 代码本身。
 *
 * `limit` 是刻意的：搜「区」会命中上千条，一次性渲染在手机上必掉帧。截断时由调用方提示。
 */
export function search(list: District[] | null, q: string, limit = 50): District[] {
  const t = q.trim().toLowerCase()
  if (!t) return []
  const out: District[] = []
  for (const d of list || []) {
    if (
      d.name.includes(t) ||
      d.shortName.includes(t) ||
      d.code.startsWith(t) ||
      d.pinyin.startsWith(t) ||
      (t.length > 1 && d.pinyinInitial.startsWith(t))
    ) {
      out.push(d)
      if (out.length >= limit) break
    }
  }
  return out
}

/** 一个码的全部祖先码（不含自身）。 */
export function ancestorsOf(index: DistrictIndex, code: string): string[] {
  const out: string[] = []
  let cur = index.byCode.get(code)
  let guard = 0
  while (cur?.parent && guard++ < 8) {
    out.push(cur.parent)
    cur = index.byCode.get(cur.parent)
  }
  return out
}

/**
 * 加一个码进选择。
 *
 * <b>祖先/后代互斥</b>：选了「广东省」再选「深圳市」是冗余的（后端展开时深圳本来就在广东里），
 * 留着只会白占 146 个名额、还让「已选 N 个」这个数字变得没法解释。规则：
 * - 已选中祖先 → 忽略这次点击（保持返回原数组，调用方据此可提示）
 * - 选中的是祖先 → 先把它的后代从选择里摘掉
 */
export function addCode(index: DistrictIndex, selected: string[], code: string): string[] {
  if (selected.includes(code)) return selected
  const ancestors = ancestorsOf(index, code)
  if (ancestors.some((a) => selected.includes(a))) return selected

  const kept = selected.filter((s) => !ancestorsOf(index, s).includes(code))
  return [...kept, code]
}

export function removeCode(selected: string[], code: string): string[] {
  return selected.filter((s) => s !== code)
}

/** 选择是否已达上限；`remaining` 可为负（回读脏数据时可能超）。 */
export function budgetOf(selected: string[]): { used: number; remaining: number; full: boolean; chars: number } {
  const chars = toCsv(selected).length
  return {
    used: selected.length,
    remaining: MAX_DISTRICTS - selected.length,
    full: selected.length >= MAX_DISTRICTS,
    chars,
  }
}

/** 某个父级下的下级；没有下级返回空数组（直辖市的区、直筒子市都是这种）。 */
export function childrenOf(index: DistrictIndex, code: string | null): District[] {
  if (!code) return index.roots
  return index.childrenOf.get(code) || []
}

/** 叶子 = 没有下级的节点（区县级，或直辖市/直筒子市里直挂省下的那一级）。判叶子只能看子级数，不能看 `level===3`。 */
export function isLeaf(index: DistrictIndex, code: string): boolean {
  return childrenOf(index, code).length === 0
}

// ============ 树形勾选（2026-08 重设计）新增的【只读派生函数】 ============
// 一律不改动上面 addCode/removeCode/ancestorsOf 的既有语义，只在其上派生三态 / 计数 / 展开。
// 红线：checkStateOf 必须【先短路自身/祖先命中】再看后代——因为「勾省只存省码」(addCode)，
// 若写成「数已选子节点 / 子节点总数」，字典日后新增一个区就会把整选的省从 checked 误翻成 indeterminate。

export type CheckState = 'checked' | 'indeterminate' | 'unchecked'

/**
 * 节点三态，顺序即正确性：
 * ① 自身或任一祖先在 `selected` → 'checked'（**不下探子树**，免疫字典漂移）；
 * ② 否则子树内有后代在 `selected` → 'indeterminate'；
 * ③ 否则 'unchecked'。
 * 对未净化数组（回读时祖先+后代同存、字典外码）也给确定结果、不抛。
 */
export function checkStateOf(index: DistrictIndex, selected: string[], code: string): CheckState {
  if (selected.includes(code)) return 'checked'
  if (ancestorsOf(index, code).some((a) => selected.includes(a))) return 'checked'
  if (selected.some((s) => ancestorsOf(index, s).includes(code))) return 'indeterminate'
  return 'unchecked'
}

// 叶子数按 index 静态缓存：3212 行只后序遍历一次，之后 O(1)，且不随 selected 变。
const leafCountCache = new WeakMap<DistrictIndex, Map<string, number>>()
function leafCounts(index: DistrictIndex): Map<string, number> {
  const hit = leafCountCache.get(index)
  if (hit) return hit
  const m = new Map<string, number>()
  const visit = (code: string): number => {
    const kids = childrenOf(index, code)
    const n = kids.length ? kids.reduce((s, k) => s + visit(k.code), 0) : 1
    m.set(code, n)
    return n
  }
  for (const r of index.roots) visit(r.code)
  leafCountCache.set(index, m)
  return m
}

/** 该节点子树内的叶子（无下级者）总数；自身即叶子时为 1；字典外码为 0。「12/21」里的分母。 */
export function leafCountOf(index: DistrictIndex, code: string): number {
  return leafCounts(index).get(code) ?? 0
}

/**
 * 子树内「被选中覆盖」的叶子数——「12/21」里的分子。短路同 `checkStateOf`：
 * checked → 全部叶子；unchecked → 0；indeterminate 才递归求子级之和（故只遍历命中分支、不裸扫 3212）。
 */
export function selectedLeafCountOf(index: DistrictIndex, selected: string[], code: string): number {
  const st = checkStateOf(index, selected, code)
  if (st === 'checked') return leafCountOf(index, code)
  if (st === 'unchecked') return 0
  return childrenOf(index, code).reduce((s, k) => s + selectedLeafCountOf(index, selected, k.code), 0)
}

/** `selected` 里每个码的祖先链并集——编辑回读 / 搜索命中时用它自动展开到目标。 */
export function defaultExpandedOf(index: DistrictIndex, selected: string[]): Set<string> {
  const out = new Set<string>()
  for (const c of selected) for (const a of ancestorsOf(index, c)) out.add(a)
  return out
}

/**
 * 点一个节点的勾选框。迁移原 `DistrictCascader.toggle` 的早退语义：
 * 已选中 → `removeCode`；被祖先覆盖 或 已达上限(`full`) → **原样返回**（调用方据引用相等判「无变化」）；否则 `addCode`。
 */
export function toggleNode(index: DistrictIndex, selected: string[], code: string, full = false): string[] {
  if (selected.includes(code)) return removeCode(selected, code)
  if (ancestorsOf(index, code).some((a) => selected.includes(a))) return selected
  if (full) return selected
  return addCode(index, selected, code)
}

/**
 * 树内过滤的取值范围。命中沿用 `search()` 的 `limit` 截断：
 * - `visible` = 命中节点 ∪ 其祖先链 = 过滤态下应渲染的 code 集合（只渲命中及祖先，**不渲命中的整棵子树**，故有界不炸）；
 * - `expand`  = 命中的祖先 = 过滤态下要自动展开的节点；
 * - `matches` = 命中本身（用于 `<mark>` 高亮）；
 * - `truncated` = 命中超过 `limit`（提示用）。
 */
export function searchScope(
  index: DistrictIndex, list: District[] | null, q: string, limit = 50,
): { visible: Set<string>; expand: Set<string>; matches: Set<string>; truncated: boolean } {
  const hits = search(list, q, limit + 1)
  const truncated = hits.length > limit
  const capped = truncated ? hits.slice(0, limit) : hits
  const visible = new Set<string>()
  const expand = new Set<string>()
  const matches = new Set<string>()
  for (const h of capped) {
    matches.add(h.code)
    visible.add(h.code)
    for (const a of ancestorsOf(index, h.code)) { visible.add(a); expand.add(a) }
  }
  return { visible, expand, matches, truncated }
}
