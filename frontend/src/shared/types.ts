// REST 契约类型 —— 与后端 controller record 对齐（决策 D2「类型即防漂移纪律」）。
// 权威来源：ActivityMarketingController + AuthConfigController + activity/domain/*。
// API 响应边界初期允许窄化断言，field-dict/create 两处加运行时守卫（见 apiClient）。

/** GET /activity-marketing/auth-config —— authEnabled=false 时只有该字段 */
export interface AuthConfig {
  authEnabled: boolean
  issuer?: string
  authorizeEndpoint?: string
  tokenEndpoint?: string
  redirectUri?: string // 后端下发的提示值；前端实际用 origin+base 派生（决策 D4）
  scope?: string
  webClients?: Array<{ tenant: string; clientId: string }>
}

/** GET /activity-marketing/field-dict —— 前端报表下拉的唯一真相源 */
export interface FieldDict {
  fields: DictField[]
  operators: DictOperator[]
  logics: Array<{ code: string; label: string }>
  activityTypes: Array<{ code: number; label: string }>
  statuses: Array<{ code: number; label: string }>
  distributionModes: Array<{ code: number; label: string }>
  strategies: string[]
}
export interface DictField {
  key: string
  label: string
  valueType: string
  operators: string[]
  enumValues: string[] | null
}
export interface DictOperator {
  code: string
  label: string
  operand: 'SCALAR' | 'RANGE' | 'LIST'
}

/** 条件树节点：分组（logic+children）或叶子（field/op/value）。id 是前端临时 key，提交前 pruneTree 剥离 */
export interface GroupNode {
  id?: string
  logic: 'AND' | 'OR'
  children: ConditionNode[]
}
export interface LeafNode {
  id?: string
  field: string
  op: string
  value: string | string[]
}
export type ConditionNode = GroupNode | LeafNode

export function isGroup(n: ConditionNode): n is GroupNode {
  return (n as GroupNode).logic !== undefined
}

/** POST /activity-marketing/create 请求体 */
export interface ActivityCreateRequest {
  requestId: string | null
  activityId: string | null
  activityName: string
  bizLine: string | null
  activityType: number
  activityRule: string | null
  activityStartTime: number | null
  activityEndTime: number | null
  activityAreaType: number
  districtIds: string | null
  priority: number | null
  inventory: number | null
  redPackageTakeType: number | null
  redPackageAmount: number | null
  redPackageAmountUnit: string
  redPackageRangeAmount: string | null
  discountStrategy: string
  eligibilityConditionTree: ConditionNode | null
  spuBindings: Array<{ storeId: number | null; spuId: number | null }> | null
  poolRefs: number[] | null
  gifts: unknown[] | null
}

/** create 响应 */
export interface ActivityCreateResult {
  activityId: string
  version: number
  status: number
  autoBoundCount: number
  idempotentHit: boolean
}

/** GET /activity-marketing/list 行 */
export interface ActivityListRow {
  activityId: string
  activityName: string
  bizLine: string | null
  activityType: number
  activityStatus: number
  version: number
}

/** POST /activity-marketing/spu-discount | /gifts 请求 */
export interface SpuDiscountRequest {
  spuIdList: number[]
  userId: number | null
  userDistrictId: string | null
  userTags: string[]
  orderAmount: number | null
  quantity: number | null
}

export interface ApiResult<T = unknown> {
  ok: boolean
  status: number
  json: T | null
  text: string
}
