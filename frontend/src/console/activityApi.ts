// 活动营销 API —— 收敛所有 /activity-marketing 调用（走 apiClient 的 marketing service）。
import { api } from '@/shared/apiClient'
import type { ServiceKey } from '@/shared/apiClient'
import type {
  ActivityListRow, ActivityCreateRequest, ActivityCreateResult,
  ConditionNode, SpuDiscountRequest, ApiResult, District,
  BulkStatusItem, BulkStatusResult,
  DiscountDecisionResponse, GiftDecisionResponse,
  AddOnOptionsResponse, AddOnQuoteResponse,
  SnapshotDiagnostics, GenerationRef,
  BindingStoreRow, BindingSpuPage,
  PickerStore, PickerProductPage,
} from '@/shared/types'

/**
 * 决策请求打哪条平面。
 *
 * - `decision` —— 只读决策服务 `/api/decision/*`（**线上真正跑的那条**：优先读代际快照）
 * - `console`  —— 写平面的 legacy 读端点 `/activity-marketing/*`（进程内无快照构建器，天然走库）
 *
 * 两者<b>不是别名关系</b>：它们的差别正是「快照 vs 库」，也正是验证页存在的意义。
 * 参数放在**尾部且可选**，既有调用点与它们的位置参数断言一律不受影响。
 */
export type DecisionPlane = 'decision' | 'console'

const SERVICE: Record<DecisionPlane, ServiceKey> = { decision: 'decision', console: 'marketing' }

export function listActivities(signal?: AbortSignal): Promise<ApiResult<ActivityListRow[]>> {
  return api<ActivityListRow[]>('marketing', 'GET', '/list', undefined, { signal })
}

export function getDetail(id: string, signal?: AbortSignal): Promise<ApiResult<Record<string, unknown>>> {
  return api('marketing', 'GET', '/' + encodeURIComponent(id), undefined, { signal })
}

/**
 * 详情回显·店铺聚合：该活动草稿基线版下每个店铺绑了多少商品（含失效）+ 多少生效。
 * 一次返回（O 店铺数），不下发万级明细。{@link getBindingSpus} 才按店铺分页取明细。
 */
export function getBindingStores(id: string, version?: number, signal?: AbortSignal): Promise<ApiResult<BindingStoreRow[]>> {
  const q = version != null ? '?version=' + version : ''
  return api<BindingStoreRow[]>('marketing', 'GET', '/' + encodeURIComponent(id) + '/binding-stores' + q, undefined, { signal })
}

/**
 * 详情回显·店铺下钻：某店铺下的绑定商品分页明细。
 * `storeId === null` 时**省略 storeId 参数**（命中「未指定门店」桶）——绝不能传空串，
 * 否则后端 `@RequestParam Integer` 空串转换会 400（见坑：null 桶传参）。
 */
export function getBindingSpus(
  id: string,
  p: { version?: number; storeId: number | null; page: number; size: number },
  signal?: AbortSignal,
): Promise<ApiResult<BindingSpuPage>> {
  const qs = new URLSearchParams()
  if (p.version != null) qs.set('version', String(p.version))
  if (p.storeId != null) qs.set('storeId', String(p.storeId))
  qs.set('page', String(p.page))
  qs.set('size', String(p.size))
  return api<BindingSpuPage>('marketing', 'GET', '/' + encodeURIComponent(id) + '/binding-spus?' + qs.toString(), undefined, { signal })
}

/**
 * 「选店铺→勾商品」picker·店铺列表：当前租户下有在架商品的店（目录浏览，编辑态用）。
 * 与 {@link getBindingStores}（按 activityId 查「已绑定」）语义不同——这是「有哪些可勾选」。
 */
export function listPickerStores(signal?: AbortSignal): Promise<ApiResult<PickerStore[]>> {
  return api<PickerStore[]>('marketing', 'GET', '/store-picker/stores', undefined, { signal })
}

/** picker·某店铺下的在架商品分页（服务端 keyword+分页）。 */
export function listPickerProducts(
  storeId: number,
  p: { keyword?: string; page: number; size: number },
  signal?: AbortSignal,
): Promise<ApiResult<PickerProductPage>> {
  const qs = new URLSearchParams()
  if (p.keyword) qs.set('keyword', p.keyword)
  qs.set('page', String(p.page))
  qs.set('size', String(p.size))
  return api<PickerProductPage>('marketing', 'GET',
    '/store-picker/stores/' + encodeURIComponent(String(storeId)) + '/products?' + qs.toString(), undefined, { signal })
}

export function createActivity(body: ActivityCreateRequest, signal?: AbortSignal): Promise<ApiResult<ActivityCreateResult>> {
  return api<ActivityCreateResult>('marketing', 'POST', '/create', body, { signal })
}

export function changeStatus(id: string, version: number, targetStatus: number): Promise<ApiResult> {
  return api('marketing', 'POST', '/' + encodeURIComponent(id) + '/status', { version, targetStatus })
}

/**
 * 批量状态变更。**部分失败一律 200**——它是正常结果不是错误，回执由调用方渲染。
 * items 必须带显式 version（见 {@link BulkStatusItem}）。
 */
export function bulkChangeStatus(items: BulkStatusItem[], targetStatus: number): Promise<ApiResult<BulkStatusResult>> {
  return api<BulkStatusResult>('marketing', 'POST', '/bulk-status', { items, targetStatus })
}

export function previewTree(tree: ConditionNode, signal?: AbortSignal): Promise<ApiResult<{ ok: boolean; message?: string; drl?: string }>> {
  return api('marketing', 'POST', '/preview', tree, { signal })
}

/**
 * 行政区划字典（全量 3212 行，约 49 KB gzip）。地域选择器的取值域。
 *
 * 一次拉全量而不是按父级懒加载，有两个理由：① 编辑既有活动时拿到的是一串裸码，
 * 要显示「广东省/深圳市/南山区」，懒加载得为每个码逐级反查祖先；② 拼音搜索本身就要求全集在手。
 */
export function listDistricts(signal?: AbortSignal): Promise<ApiResult<District[]>> {
  return api<District[]>('marketing', 'GET', '/districts', undefined, { signal })
}

export function spuDiscount(body: SpuDiscountRequest, signal?: AbortSignal,
                            plane: DecisionPlane = 'decision'): Promise<ApiResult<DiscountDecisionResponse>> {
  return api<DiscountDecisionResponse>(SERVICE[plane], 'POST', '/spu-discount', body, { signal })
}

export function queryGifts(body: SpuDiscountRequest, signal?: AbortSignal,
                           plane: DecisionPlane = 'decision'): Promise<ApiResult<GiftDecisionResponse>> {
  return api<GiftDecisionResponse>(SERVICE[plane], 'POST', '/gifts', body, { signal })
}

export function queryAddOnOptions(body: SpuDiscountRequest, signal?: AbortSignal,
                                  plane: DecisionPlane = 'decision'): Promise<ApiResult<AddOnOptionsResponse>> {
  return api<AddOnOptionsResponse>(SERVICE[plane], 'POST', '/addon/options', body, { signal })
}

export function quoteAddOn(
  body: SpuDiscountRequest,
  activityId: string,
  itemName: string,
  signal?: AbortSignal,
  plane: DecisionPlane = 'decision',
): Promise<ApiResult<AddOnQuoteResponse>> {
  const query = '?activityId=' + encodeURIComponent(activityId) + '&item=' + encodeURIComponent(itemName)
  return api<AddOnQuoteResponse>(SERVICE[plane], 'POST', '/addon/quote' + query, body, { signal })
}

/**
 * 快照诊断：这个活动在不在决策服务当前的快照里。
 *
 * 只打 decision 平面——console 上没有这个端点，也不该有：它问的就是「**决策服务**眼里是什么样」。
 */
export function snapshotDiagnostics(activityId?: string, signal?: AbortSignal): Promise<ApiResult<SnapshotDiagnostics>> {
  const query = activityId ? '?activityId=' + encodeURIComponent(activityId) : ''
  return api<SnapshotDiagnostics>('decision', 'GET', '/snapshot' + query, undefined, { signal })
}

/** 库里当前的发布代际——决策侧回显的 generation 的参照物。 */
export function currentGeneration(bizLine?: string, signal?: AbortSignal): Promise<ApiResult<GenerationRef>> {
  const query = bizLine ? '?bizLine=' + encodeURIComponent(bizLine) : ''
  return api<GenerationRef>('marketing', 'GET', '/generation' + query, undefined, { signal })
}
