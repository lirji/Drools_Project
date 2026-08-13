// 行政区划字典缓存。**不按租户分片**——行政区划是国家标准，不是租户数据（后端 sys_district 无 @TenantId）。
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listDistricts } from '@/console/activityApi'
import type { District } from '@/shared/types'

export const useDistrictStore = defineStore('district', () => {
  const items = ref<District[] | null>(null)
  /** 上一次加载失败（用于把「还没拉」与「拉过但挂了」区分开——两者的界面文案不一样）。 */
  const failed = ref(false)

  // 同一 tick 里多个组件同时 load 只应打一次网。useDictStore 没做这层，新写的别继承那个缺口。
  let inflight: Promise<District[] | null> | null = null

  async function load(signal?: AbortSignal): Promise<District[] | null> {
    if (items.value) return items.value
    if (inflight) return inflight
    inflight = (async () => {
      const r = await listDistricts(signal)
      if (r.ok && Array.isArray(r.json)) {
        items.value = r.json
        failed.value = false
        return r.json
      }
      // 只在**不是取消**的情况下记失败：路由切走导致的 abort 不该让界面报「字典加载失败」。
      if (!signal?.aborted) failed.value = true
      return null
    })()
    try {
      return await inflight
    } finally {
      inflight = null
    }
  }

  function clear(): void {
    items.value = null
    failed.value = false
  }

  return { items, failed, load, clear }
})
