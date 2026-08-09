// 活动营销 API —— 收敛所有 /activity-marketing 调用（走 apiClient 的 marketing service）。
import { api } from '@/shared/apiClient'
import type {
  ActivityListRow, ActivityCreateRequest, ActivityCreateResult,
  ConditionNode, SpuDiscountRequest, ApiResult,
  BulkStatusItem, BulkStatusResult,
} from '@/shared/types'

export function listActivities(signal?: AbortSignal): Promise<ApiResult<ActivityListRow[]>> {
  return api<ActivityListRow[]>('marketing', 'GET', '/list', undefined, { signal })
}

export function getDetail(id: string, signal?: AbortSignal): Promise<ApiResult<Record<string, unknown>>> {
  return api('marketing', 'GET', '/' + encodeURIComponent(id), undefined, { signal })
}

export function createActivity(body: ActivityCreateRequest, signal?: AbortSignal): Promise<ApiResult<ActivityCreateResult>> {
  return api<ActivityCreateResult>('marketing', 'POST', '/create', body, { signal })
}

export function changeStatus(id: string, version: number, targetStatus: number): Promise<ApiResult> {
  return api('marketing', 'POST', '/' + encodeURIComponent(id) + '/status', { version, targetStatus })
}

/**
 * 批量上下线。**部分失败一律 200**——它是正常结果不是错误，回执由调用方渲染。
 * items 必须带显式 version（见 {@link BulkStatusItem}）。
 */
export function bulkChangeStatus(items: BulkStatusItem[], targetStatus: number): Promise<ApiResult<BulkStatusResult>> {
  return api<BulkStatusResult>('marketing', 'POST', '/bulk-status', { items, targetStatus })
}

export function previewTree(tree: ConditionNode, signal?: AbortSignal): Promise<ApiResult<{ ok: boolean; message?: string; drl?: string }>> {
  return api('marketing', 'POST', '/preview', tree, { signal })
}

export function spuDiscount(body: SpuDiscountRequest, signal?: AbortSignal): Promise<ApiResult<Record<string, unknown>>> {
  return api('marketing', 'POST', '/spu-discount', body, { signal })
}

export function queryGifts(body: SpuDiscountRequest, signal?: AbortSignal): Promise<ApiResult<Record<string, unknown>>> {
  return api('marketing', 'POST', '/gifts', body, { signal })
}
