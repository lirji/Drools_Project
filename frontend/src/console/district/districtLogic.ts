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
