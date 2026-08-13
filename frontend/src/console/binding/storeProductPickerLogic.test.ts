import { describe, expect, it } from 'vitest'
import { newPairs, pairKey } from './storeProductPickerLogic'

describe('storeProductPickerLogic', () => {
  it('pairKey 用 storeId#spuId 复合键', () => {
    expect(pairKey(1, 9101)).toBe('1#9101')
    expect(pairKey(1, 9101)).not.toBe(pairKey(2, 9101))
  })

  it('newPairs 剔除与 existing 重复的对', () => {
    const existing = [{ storeId: 1, spuId: 9101 }]
    const fresh = newPairs(existing, [
      { storeId: 1, spuId: 9101 }, // 已存在 → 剔除
      { storeId: 1, spuId: 9102 }, // 新
      { storeId: 2, spuId: 9101 }, // 不同店 → 新
    ])
    expect(fresh).toEqual([{ storeId: 1, spuId: 9102 }, { storeId: 2, spuId: 9101 }])
  })

  it('newPairs 对 additions 内部也去重', () => {
    const fresh = newPairs([], [
      { storeId: 1, spuId: 9101 },
      { storeId: 1, spuId: 9101 },
    ])
    expect(fresh).toEqual([{ storeId: 1, spuId: 9101 }])
  })
})
