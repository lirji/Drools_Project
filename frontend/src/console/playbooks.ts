/**
 * 玩法模板目录（PR-6）。
 *
 * <p><b>大多数模板不新增后端能力</b>——后端只有两个活动类型（红包 1 / 买赠 5），
 * 权益形态三种（固定金额 / 阶梯金额 / 折扣，后者是 2026-08 新增）。这些类型配上 6 个可用条件字段
 * （订单金额 / 购买数量 / 用户地域 / 用户标签 / 商品 SPU / 店铺），本来就能表达好几种玩法，
 * 只是过去没有名字——运营在编辑器里看到的是「活动类型：红包」，看不出自己能配出「满 300 减 50」
 * 还是「新客专享立减」。这一屏做的就是**给已有能力起名字并给出起点**。
 *
 * <p>与之相对，<b>不可用的玩法一张都不删</b>，而是标灰并写明缺什么（决策记录 D6：绝不用假数据糊弄，
 * 也不用「敬请期待」搪塞）。运营看得到边界，才不会拿一个配不出来的玩法去排期。
 */

/**
 * **写平面当前放行的活动类型**——与后端 `ActivityMarketingService.validateCommon` 的白名单同源。
 *
 * <p>它是这一屏与后端之间唯一的能力契约。加价购(6) 的决策链路早就通了，但写入口至今只放行 1/5，
 * 于是「用它新建」会在保存时吃一个 400；目录层却因为只判「有没有 preset」而照样把卡片标成可用。
 * 把类型白名单提出来、让 {@link isReady} 从它推导，是为了让这种「目录跑到能力前面」在结构上不可能发生——
 * 将来后端放行 6，改这一行就够了，不必再去逐张卡片回忆哪些该点亮。
 *
 * <p>编辑器的活动类型下拉（`EditorView.enabledTypes`）也读这里，两处不会再各写一份。
 */
export const CREATABLE_ACTIVITY_TYPES: number[] = [1, 5]

/** 分组。blocked = 后端确实做不到，卡上必须写明缺什么 */
export type PlaybookGroup = 'reduce' | 'targeted' | 'gift' | 'blocked'

export interface PlaybookCondition {
  /** field-dict 的 field key */
  field: string
  /** field-dict 的 operator code */
  op: string
  value: string
}

export interface PlaybookPreset {
  /** 1 = 红包，5 = 买赠，6 = 加价购 */
  activityType: 1 | 5 | 6
  /**
   * 与后端 BenefitForm 对齐：
   * - fixed/ladder = 金额型（amount 是要减的钱）
   * - ratio        = 折扣型（amount 是折数，作用于整单）
   * - price        = 一口价（amount 是"卖多少"，unit='价'）
   * - nth          = 第 N 件折（amount 是折数，nth 是第几件，unit='件折'）
   */
  redMode: 'fixed' | 'ladder' | 'ratio' | 'price' | 'nth'
  amount?: number
  /** 第 N 件折的 N（≥2）。仅 redMode='nth' 有意义 */
  nth?: number
  /** 折扣型的封顶减免额。折扣型**必填**——写平面会拒掉没有封顶的折扣券 */
  maxDiscount?: number
  ladder?: Array<{ min: number; max: number | ''; reward: number }>
  strategy?: string
  conditions?: PlaybookCondition[]
}

export interface Playbook {
  id: string
  name: string
  /** 一句人话——运营读完就知道这玩法是什么，不需要理解「活动类型」这个字段 */
  plain: string
  group: PlaybookGroup
  /** 迷你票据预览：让「满 300 减 50」长成一张券的样子，而不是一行参数 */
  receipt: Array<{ label: string; amount: number }>
  /** 可用时给预填；不可用时为 undefined */
  preset?: PlaybookPreset
  /** 不可用时必须写明**缺什么**，不许写「敬请期待」 */
  blockedReason?: string
}

const MAX = 'MAX'

export const PLAYBOOKS: Playbook[] = [
  // ─────────────── 满减类（红包 · 固定/阶梯金额） ───────────────
  {
    id: 'flat',
    name: '无门槛立减',
    plain: '任何订单都直接减固定金额，没有门槛。适合拉新和清尾货。',
    group: 'reduce',
    receipt: [{ label: '任意订单', amount: 10 }],
    preset: { activityType: 1, redMode: 'fixed', amount: 10, strategy: MAX },
  },
  {
    id: 'threshold',
    name: '满 X 减 Y',
    plain: '订单金额达到门槛就减固定金额。最常用的一种，用来抬客单价。',
    group: 'reduce',
    receipt: [{ label: '满 200 元', amount: 20 }],
    preset: {
      activityType: 1, redMode: 'fixed', amount: 20, strategy: MAX,
      conditions: [{ field: 'orderAmount', op: 'ge', value: '200' }],
    },
  },
  {
    id: 'ladder',
    name: '阶梯满减',
    plain: '按订单金额分多档，买得越多减得越多，只享受命中的那一档。',
    group: 'reduce',
    receipt: [
      { label: '满 300 元', amount: 50 },
      { label: '满 600 元', amount: 120 },
      { label: '满 1000 元', amount: 220 },
    ],
    preset: {
      activityType: 1, redMode: 'ladder', strategy: MAX,
      ladder: [
        { min: 300, max: 600, reward: 50 },
        { min: 600, max: 1000, reward: 120 },
        { min: 1000, max: '', reward: 220 },
      ],
    },
  },
  {
    id: 'quantity',
    name: '满 N 件立减',
    plain: '购买件数达到门槛就减固定金额。注意是「减钱」不是「打折」。',
    group: 'reduce',
    receipt: [{ label: '满 2 件', amount: 15 }],
    preset: {
      activityType: 1, redMode: 'fixed', amount: 15, strategy: MAX,
      conditions: [{ field: 'quantity', op: 'ge', value: '2' }],
    },
  },

  {
    id: 'discount',
    name: '折扣券（打 X 折）',
    plain: '按比例打折，比如全场 8 折，并设一个封顶减免额。',
    group: 'reduce',
    receipt: [{ label: '订单 8 折 · 最多减 50', amount: 50 }],
    preset: {
      activityType: 1, redMode: 'ratio', amount: 8, maxDiscount: 50, strategy: MAX,
    },
  },

  // ─────────────── 定向类（红包 + 人群/门店/地域条件） ───────────────
  {
    id: 'tagged',
    name: '人群定向券',
    plain: '只有带指定标签的用户能享受，比如新客、高价值、沉睡召回。',
    group: 'targeted',
    receipt: [{ label: '高价值用户', amount: 30 }],
    preset: {
      activityType: 1, redMode: 'fixed', amount: 30, strategy: MAX,
      conditions: [{ field: 'userTags', op: 'contains', value: '高价值' }],
    },
  },
  {
    id: 'store',
    name: '门店定向立减',
    plain: '只在指定门店下单才享受。用来给单店做活动而不影响全国。',
    group: 'targeted',
    receipt: [{ label: '指定门店', amount: 12 }],
    preset: {
      activityType: 1, redMode: 'fixed', amount: 12, strategy: MAX,
      conditions: [{ field: 'storeId', op: 'eq', value: '1' }],
    },
  },
  {
    id: 'region',
    name: '地域定向立减',
    plain: '只有指定地域的用户能享受。用来做区域性投放。',
    group: 'targeted',
    receipt: [{ label: '指定地域', amount: 15 }],
    preset: {
      activityType: 1, redMode: 'fixed', amount: 15, strategy: MAX,
      conditions: [{ field: 'userDistrictId', op: 'eq', value: '310000' }],
    },
  },

  // ─────────────── 赠品类（买赠） ───────────────
  {
    id: 'gift',
    name: '满额赠品',
    plain: '订单金额达标就送赠品，不减钱。赠品清单在编辑器第 2 步配。',
    group: 'gift',
    receipt: [{ label: '满 500 元送赠品', amount: 0 }],
    preset: {
      activityType: 5, redMode: 'fixed', strategy: MAX,
      conditions: [{ field: 'orderAmount', op: 'ge', value: '500' }],
    },
  },

  // ─────────────── 曾经不可用、现已解锁（决策入口补订单行 + 一口价 + 两阶段） ───────────────
  {
    id: 'second-half',
    name: '第二件半价',
    plain: '同款买两件，第二件半价。',
    group: 'targeted',
    receipt: [{ label: '第 2 件', amount: 5 }],
    preset: {
      activityType: 1, redMode: 'nth', amount: 5, nth: 2, strategy: MAX,
      conditions: [],
    },
  },
  {
    id: 'flash',
    name: '限时秒杀（一口价）',
    plain: '活动期内直接按一口价卖，不是在原价上减。',
    group: 'reduce',
    receipt: [{ label: '一口价', amount: 9.9 }],
    preset: {
      activityType: 1, redMode: 'price', amount: 9.9, strategy: MAX,
      conditions: [],
    },
  },
  // 加价购的**决策侧**已经通了（两阶段 /decision/v1/addon/{options,quote}），但写平面建不出来：
  // ActivityMarketingService.validateCommon 只放行红包(1)/买赠(5)，type=6 直接 400。
  // 它一度带着 preset 挂在「赠品类」里给「用它新建」——运营填完整张表才在保存时撞墙。
  // 按 D6 的规矩退回 blocked 并写明缺什么，比留一张点得动的死卡诚实。
  {
    id: 'addon',
    name: '加价购',
    plain: '买主商品后，可以加少量钱换购指定商品。',
    group: 'blocked',
    receipt: [{ label: '加 9.9 元换购', amount: 9.9 }],
    blockedReason:
      '决策侧的两阶段换购（列选项 → 权威报价）已经能跑，缺的是写入口：'
      + '写平面的活动类型白名单只放行红包与买赠，加价购（类型 6）保存时会被拒；'
      + '编辑器也还没有配换购品清单的地方。这两件事补齐后这张卡才会点亮。',
  },
]

export const PLAYBOOK_GROUPS: Array<{ key: PlaybookGroup | 'all'; label: string }> = [
  { key: 'all', label: '全部' },
  { key: 'reduce', label: '满减类' },
  { key: 'targeted', label: '定向类' },
  { key: 'gift', label: '赠品类' },
  { key: 'blocked', label: '暂不支持' },
]

export function findPlaybook(id: string | null | undefined): Playbook | null {
  if (!id) return null
  return PLAYBOOKS.find((p) => p.id === id) ?? null
}

export function countByGroup(key: PlaybookGroup | 'all'): number {
  return key === 'all' ? PLAYBOOKS.length : PLAYBOOKS.filter((p) => p.group === key).length
}

export function filterPlaybooks(key: PlaybookGroup | 'all'): Playbook[] {
  return key === 'all' ? PLAYBOOKS : PLAYBOOKS.filter((p) => p.group === key)
}

/**
 * 可用 = 有 preset **且**这个活动类型写平面真的收。
 *
 * <p>第二个条件不是冗余：`addon` 就是靠只判第一个条件混成「可用」的——决策侧能跑不等于建得出来，
 * 而运营是在填完整张表、点了保存之后才知道的。判据必须落在「写入口收不收」上。
 */
export function isReady(p: Playbook): boolean {
  return p.preset !== undefined && CREATABLE_ACTIVITY_TYPES.includes(p.preset.activityType)
}
