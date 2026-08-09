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
  /** 1 = 红包，5 = 买赠。后端 create 只放行这两个 */
  activityType: 1 | 5
  /** 与后端 BenefitForm 对齐：fixed/ladder = 金额型，ratio = 折扣型（amount 是折数） */
  redMode: 'fixed' | 'ladder' | 'ratio'
  amount?: number
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

  // ─────────────── 暂不可用：逐条写明缺什么 ───────────────
  {
    id: 'second-half',
    name: '第二件半价',
    plain: '同款买两件，第二件半价。',
    group: 'blocked',
    receipt: [{ label: '第 2 件', amount: 0 }],
    blockedReason:
      '决策入口 SpuDiscountRequest 只有 spuIdList / orderAmount / quantity，**没有逐行单价**，'
      + '算不出「第二件」是哪一件、值多少钱。要支持必须先加行项模型（订单行 + 单价），'
      + '那是入口契约的破坏性升级。',
  },
  {
    id: 'flash',
    name: '限时秒杀（一口价）',
    plain: '活动期内直接按一口价卖，不是在原价上减。',
    group: 'blocked',
    receipt: [{ label: '一口价', amount: 0 }],
    blockedReason:
      '现有权益形态表达的都是「减多少」，没有「卖多少」这种一口价形态。'
      + '而且秒杀要靠库存扣减防超发，当前 inventory 是声明式、决策链路不读取不扣减。',
  },
  {
    id: 'addon',
    name: '加价购',
    plain: '买主商品后，可以加少量钱换购指定商品。',
    group: 'blocked',
    receipt: [{ label: '加 9.9 元换购', amount: 0 }],
    blockedReason:
      '需要两阶段决策：先判主商品是否命中，再返回可换购清单让用户选，选完二次定价。'
      + '当前决策链路是一次性返回最终优惠，没有第二阶段。',
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

/** 可用 = 有 preset。不可用的卡不给「用它新建」，否则运营会配出一个保存不了的活动 */
export function isReady(p: Playbook): boolean {
  return p.preset !== undefined
}
