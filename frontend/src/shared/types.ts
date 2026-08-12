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

/**
 * bulk-status 回执。**部分失败是正常结果**——逐条独立事务，一条失败不回滚已成功的，
 * 由前端渲染 `failed[]`，此时 HTTP 仍是 200。
 *
 * 唯一的例外是 `targetStatus` **本身**非法（不在 0/1/2 内）：那不是「某几条没成功」，
 * 而是整个请求没意义，服务端在进循环之前就返回 400（否则几十条会各自失败一次、
 * 回执里全是同一句话）。前端当前不可触发——`askBulk` 只传 1|2。
 */
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

/**
 * 单个候选活动对本次决策的贡献。
 *
 * `applied=false` 时 `rejectReason` 说明为什么没生效（资格不满足 / 阶梯未落档 /
 * 一口价高于订单金额 / 缺订单行 …）。**落选者也在列表里**——运营验证时最想知道的
 * 恰恰是「我配的那个活动为什么没生效」，而不只是「最后减了多少」。
 */
export interface DiscountDecisionItem {
  activityId: string
  activityName: string | null
  version: number | null
  /** 与后端 BenefitForm 同源：AMOUNT / RATIO_ZHE / FIXED_PRICE / NTH_ZHE */
  benefitForm: string
  /** 该活动自己算出的减免，**不受订单级封顶影响** */
  amount: number
  applied: boolean
  rejectReason: string | null
}

/**
 * 这次决策的物料是从哪来的（后端 `DecisionProvenance`）。
 *
 * **必须是可选字段**：灰度期后端可能还没回传，此时 UI 要渲染成「后端未回传」而不是默认成 db——
 * 默认成 db 等于替后端说了一句它没说过的话，而这句话恰好是运营用来判断该不该信任结论的那句。
 */
export interface DecisionProvenance {
  /** 'snapshot' = 代际快照（零查询）；'db' = 逐请求查库 */
  source: 'snapshot' | 'db'
  /** 参与本次决策的快照桶里**最落后**的那一代；走库时为 null */
  generation: number | null
  /** 参与本次决策的快照桶数。>1 时 generation 是下确界而非某个桶的真值 */
  buckets: number
}

export interface DiscountDecisionResponse {
  hit: boolean
  hitActivityId: string | null
  hitActivityName: string | null
  hitAmount: number
  strategy: string
  traces: string[]
  mode: string
  /** 命中活动的版本号——「这笔钱按哪一版算的」 */
  hitVersion: number | null
  /** 减免额是否被订单金额截断过。true 基本等价于「这个活动配错了」 */
  clamped: boolean
  /** 本次决策的对账锚点，与结构化日志同值；不落库 */
  decisionId: string
  /** 物料来源。没有它，验证页照出来的永远是自己那条路的结论 */
  provenance?: DecisionProvenance
  /** 逐活动明细。STACK 下多个活动同时出钱，只看 hitAmount 是看不出构成的 */
  items: DiscountDecisionItem[]
}

export interface GiftDecisionItem {
  /** 这件赠品由哪个活动、哪一版送出——否则收到一堆赠品名不知道是谁送的 */
  activityId: string | null
  version: number | null
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
  decisionId: string
  provenance?: DecisionProvenance
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
  provenance?: DecisionProvenance
  /** 对账锚点，与审计日志同值。加价购此前没有它，工单拿着 id 什么也查不到 */
  decisionId?: string
}

export interface AddOnQuoteResponse {
  ok: boolean
  activityId: string | null
  itemName: string | null
  addOnPrice: number | null
  reason: string | null
  traces: string[]
  /** 第二阶段的 provenance 来自 quote **自己那次**重新装载，不是第一阶段的 */
  provenance?: DecisionProvenance
  /** 两阶段共用同一个 id：一次 quote 就是一次决策，内部那次重新装载是它的一部分 */
  decisionId?: string
}

export interface ApiResult<T = unknown> {
  ok: boolean
  status: number
  json: T | null
  text: string
}

/** 快照诊断端点 `GET /decision/v1/snapshot` 的回执。 */
export interface SnapshotBucket {
  bizLine: string | null
  generation: number
  builtAt: string | null
  ageSeconds: number | null
  activityCount: number
  containsActivity?: boolean
}

export interface SnapshotDiagnostics {
  tenant: string | null
  buckets: SnapshotBucket[]
  bucketCount: number
  activityId?: string
  /** 这个活动在不在本租户的任何快照桶里——「三个值全绿但活动不命中」时唯一说得出话的读数 */
  inSnapshot?: boolean
  hostedByBizLines?: string[]
  hint?: string
}

/** 写平面 `GET /activity-marketing/generation` 的回执，用作决策侧 generation 的参照物。 */
export interface GenerationRef {
  bizLine: string
  generation: number
  note: string
}
