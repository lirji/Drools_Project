// 活动营销 API —— 收敛所有 /activity-marketing 调用（走 apiClient 的 marketing service）。
import { api } from '@/shared/apiClient'
import type {
  ActivityListRow, ActivityCreateRequest, ActivityCreateResult,
  ConditionNode, SpuDiscountRequest, ApiResult,
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

export function previewTree(tree: ConditionNode, signal?: AbortSignal): Promise<ApiResult<{ ok: boolean; message?: string; drl?: string }>> {
  return api('marketing', 'POST', '/preview', tree, { signal })
}

export function spuDiscount(body: SpuDiscountRequest, signal?: AbortSignal): Promise<ApiResult<Record<string, unknown>>> {
  return api('marketing', 'POST', '/spu-discount', body, { signal })
}

export function queryGifts(body: SpuDiscountRequest, signal?: AbortSignal): Promise<ApiResult<Record<string, unknown>>> {
  return api('marketing', 'POST', '/gifts', body, { signal })
}
