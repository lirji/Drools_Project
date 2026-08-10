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
  /** 权益形态判别位：'元' = 金额型（amount 是钱），'折' = 折扣型（amount 是折数） */
  redPackageAmountUnit: string
  /** 折扣型的封顶减免额（元）。后端对折扣型强制非空——不封顶等于无上限支出 */
  redPackageMaxDiscount?: number | null
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
  /** 配置被接受、但当前实现不会执行的部分（如库存声明式不扣减）。见 DECISION_RECORD D12-3。 */
  warnings?: string[]
}

/** GET /activity-marketing/list 行 */
export interface ActivityListRow {
  activityId: string
  activityName: string
  bizLine: string | null
  activityType: number
  activityStatus: number
  version: number
  /** 以下三项后端 list() 一直返回（它返回的是完整 ActivityManageEntity），只是此前 TS 没声明。 */
  activityStartTime?: string | number | null
  activityEndTime?: string | number | null
  inventory?: number | null
}

/** POST /activity-marketing/bulk-status —— 一项 = 「哪个活动的哪一版」。
 *  必须带显式 version：P0-4 之后线上版与草稿并存，不传版本会打到草稿、线上继续发钱。 */
export interface BulkStatusItem {
  activityId: string
  version: number
}

/** bulk-status 回执。部分失败是正常结果（HTTP 恒 200），由前端渲染。 */
export interface BulkStatusResult {
  succeeded: string[]
  failed: Array<{ activityId: string; reason: string }>
}

/** 决策订单行：第 N 件折必须拿到逐行单价，不能用整单均价反推。 */
export interface DecisionOrderLine {
  spuId: number
  unitPrice: number
  quantity: number
}

/** POST /activity-marketing/spu-discount | /gifts | /addon/options | /addon/quote 请求 */
export interface SpuDiscountRequest {
  spuIdList: number[]
  userId: number | null
  userDistrictId: string | null
  userTags: string[]
  orderAmount: number | null
  quantity: number | null
  /** 「这一单来自哪个门店」。条件白名单里的 storeId 靠它取值——此前后端入参没有该字段，
   *  导致配了 storeId 条件的活动永远不命中（静默不发）。见 DECISION_RECORD D12-4。 */
  storeId: number | null
  /** 明细模式的唯一商品事实源；普通模式传 null。 */
  lines: DecisionOrderLine[] | null
}

export interface DiscountDecisionResponse {
  hit: boolean
  hitActivityId: string | null
  hitActivityName: string | null
  hitAmount: number
  strategy: string
  traces: string[]
  mode: string
}

export interface GiftDecisionItem {
  batchId: string | null
  giftName: string | null
  giftType: string | null
  giftNum: number | null
  absoluteAmount: number | null
  rightType: string | null
}

export interface GiftDecisionResponse {
  gifts: GiftDecisionItem[]
  traces: string[]
  mode: string
}

export interface AddOnOption {
  activityId: string
  activityName: string
  version: number
  itemName: string
  addOnPrice: number
}

export interface AddOnOptionsResponse {
  options: AddOnOption[]
  traces: string[]
}

export interface AddOnQuoteResponse {
  ok: boolean
  activityId: string | null
  itemName: string | null
  addOnPrice: number | null
  reason: string | null
  traces: string[]
}

export interface ApiResult<T = unknown> {
  ok: boolean
  status: number
  json: T | null
  text: string
}
