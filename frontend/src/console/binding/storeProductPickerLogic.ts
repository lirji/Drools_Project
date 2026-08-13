/**
 * StoreProductPicker 的纯逻辑层（可单测，仿 districtLogic.ts）。
 * 只管「(storeId,spuId) 复合键」的去重——picker 内部选择去重、以及并入 dr.spu 时对既有行去重。
 */

export interface SpuPair {
  storeId: number
  spuId: number
}

/** 复合键。storeId/spuId 可能是 number 或手填的 string，统一按字符串拼。 */
export function pairKey(storeId: number | string | null, spuId: number | string | null): string {
  return `${storeId}#${spuId}`
}

/**
 * 从 additions 里过滤掉已在 existing 中的 (storeId,spuId)，返回去重后的新增项（保序、内部也去重）。
 * 用于 EditorView.onPickerAppend：picker 勾选结果并入 dr.spu 前，剔除与手填行/已勾选重复的对。
 */
export function newPairs(existing: SpuPair[], additions: SpuPair[]): SpuPair[] {
  const seen = new Set(existing.map((e) => pairKey(e.storeId, e.spuId)))
  const out: SpuPair[] = []
  for (const a of additions) {
    const k = pairKey(a.storeId, a.spuId)
    if (!seen.has(k)) {
      seen.add(k)
      out.push(a)
    }
  }
  return out
}
