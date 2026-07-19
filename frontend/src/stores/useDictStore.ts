// field-dict 缓存（按租户+bizLine）。登出/切租清空，避免跨租户字段污染。
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/shared/apiClient'
import type { FieldDict } from '@/shared/types'

export const useDictStore = defineStore('dict', () => {
  const cache = ref<Record<string, FieldDict>>({})

  function keyOf(bizLine?: string): string {
    return bizLine || '__default__'
  }

  async function load(bizLine?: string): Promise<FieldDict | null> {
    const k = keyOf(bizLine)
    if (cache.value[k]) return cache.value[k]
    const path = bizLine ? '/field-dict?bizLine=' + encodeURIComponent(bizLine) : '/field-dict'
    const r = await api<FieldDict>('marketing', 'GET', path)
    if (r.ok && r.json) {
      cache.value = { ...cache.value, [k]: r.json }
      return r.json
    }
    return null
  }

  function clear(): void {
    cache.value = {}
  }

  return { cache, load, clear }
})
