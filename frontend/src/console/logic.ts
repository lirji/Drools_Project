// 条件树与表单的纯逻辑 —— 平移自 activity.js（pruneTree/cleanLadder/parseLadder/时间转换等）。
// 抽成纯函数是 Vitest 回报最高处（决策 D6：树不变量 + 校验）。渲染期绝不 mutate（Vue 响应式约定）。
import type { ConditionNode, GroupNode, LeafNode, DictField, DictOperator } from '@/shared/types'
import { isGroup } from '@/shared/types'

// 稳定节点 id：递归组件 :key 必须用它，禁 index 作 key（删中间行会串值——30 号决策已实证）。
let _seq = 0
export function nodeId(): string {
  _seq += 1
  return 'n' + _seq.toString(36) + '-' + (_seq * 2654435761 % 100000).toString(36)
}

/** 给整棵树补齐临时 id（载入/新建时调用一次） */
export function assignIds(node: ConditionNode): ConditionNode {
  if (!node.id) node.id = nodeId()
  if (isGroup(node)) node.children.forEach(assignIds)
  return node
}

/** 提交前剪空组 + 剥离临时 id（空树返回 null=恒通过）。不 mutate 入参。 */
export function pruneTree(node: ConditionNode | null): ConditionNode | null {
  if (!node) return null
  if (isGroup(node)) {
    const kids = node.children
      .map(pruneTree)
      .filter((x): x is ConditionNode => x !== null)
    if (!kids.length) return null
    return { logic: node.logic, children: kids }
  }
  // 叶子：剥 id
  const leaf = node as LeafNode
  return { field: leaf.field, op: leaf.op, value: leaf.value }
}

export interface LadderRow {
  min: number | string
  max: number | string
  reward: number | string
}

/**
 * 红包权益的六种一等形态。URL 不携带这个判别结果；编辑与复核都必须从 rule 数据导出。
 */
export type BenefitForm = 'fixed' | 'random' | 'ladder' | 'ratio' | 'price' | 'nth'

/** 判别所需的最小后端 rule 形状，兼容详情接口与纯函数测试。 */
export interface BenefitRuleLike {
  redPackageTakeType?: number | null
  redPackageAmount?: number | null
  redPackageAmountUnit?: string | null
  redPackageMaxDiscount?: number | null
  redPackageRangeAmount?: string | null
}

/** 编辑器中由权益形态拥有的字段；不包含名称、条件树等活动级字段。 */
export interface BenefitDraftFields {
  redMode: BenefitForm
  amount: number | string
  maxDiscount: number | string
  rangeMin: number | string
  rangeMax: number | string
  nth: number | string
  ladder: LadderRow[]
}

export interface BenefitRequestFields {
  redPackageTakeType: number | null
  redPackageAmount: number | null
  redPackageAmountUnit: string
  redPackageMaxDiscount: number | null
  redPackageRangeAmount: string | null
}

/** 阶梯档清洗：只留有 reward 的行，归一 min/max/reward（平移 cleanLadder） */
export function cleanLadder(rows: LadderRow[]): Array<{ min: number; max: number | null; reward: number }> {
  return rows
    .filter((r) => r.reward !== '' && r.reward != null)
    .map((r) => ({
      min: r.min === '' ? 0 : Number(r.min),
      max: r.max === '' ? null : Number(r.max),
      reward: Number(r.reward),
    }))
}

/** 解析后端 redPackageRangeAmount JSON → 表单阶梯行（平移 parseLadder） */
export function parseLadder(json: string): LadderRow[] {
  try {
    const arr = JSON.parse(json) as unknown
    if (!Array.isArray(arr)) return []
    return arr.map((t: { min?: number; max?: number; reward?: number }) => ({
      min: t.min == null ? 0 : t.min,
      max: t.max == null ? '' : t.max,
      reward: t.reward == null ? '' : t.reward,
    }))
  } catch {
    return []
  }
}

/**
 * 解析「第 N 件折」的 N —— 与后端 `RandomRangeParser.parseNth` 同规矩。
 *
 * <p>`redPackageRangeAmount` 是三用途列（数组=阶梯、`{min,max}`=随机区间、`{nth:N}`=第 N 件），
 * 这里只认对象形态的 `nth`，且 **N<2 视为无效**：N=1 等于全场打折，那是折扣型，配成 1 更像配错。
 * 解析不出来返回 null，由调用方决定怎么提示——绝不回落成某个默认值，那会让一次编辑改掉发放规则。
 */
export function parseNth(json: string | null | undefined): number | null {
  if (!json) return null
  try {
    const o = JSON.parse(json) as { nth?: unknown }
    if (!o || typeof o !== 'object' || Array.isArray(o)) return null
    const n = Number(o.nth)
    return Number.isInteger(n) && n >= 2 ? n : null
  } catch {
    return null
  }
}

/**
 * 解析随机红包的区间 —— 与后端 `RandomRangeParser.parse` 同规矩。
 *
 * <p>与 {@link parseNth} 共用「对象形态」，靠 `redPackageAmountUnit` + `redPackageTakeType`
 * 区分用途，不靠猜键名。min>max / 负数 / 缺字段一律 null（不可计算），由调用方决定怎么提示。
 */
export function parseRandomRange(json: string | null | undefined): { min: number; max: number } | null {
  if (!json) return null
  try {
    const o = JSON.parse(json) as { min?: unknown; max?: unknown }
    if (!o || typeof o !== 'object' || Array.isArray(o)) return null
    const min = Number(o.min)
    const max = Number(o.max)
    if (!Number.isFinite(min) || !Number.isFinite(max)) return null
    if (min < 0 || max < 0 || min > max) return null
    return { min, max }
  } catch {
    return null
  }
}

/**
 * 全前端唯一的权益形态判别函数。
 *
 * `form` 回答“这段数据归哪种形态所有”，`parsed` 回答“该形态的结构化载荷能否使用”。
 * 两者必须分开：历史脏阶梯/随机区间仍归原形态，但编辑器会要求重填，复核屏会明确报损坏；
 * 绝不能因为解析失败就回落 fixed，并在下一次保存时把 range 静默抹掉。
 */
export function benefitFormOf(rule: BenefitRuleLike | null | undefined): { form: BenefitForm; parsed: boolean } {
  if (!rule) return { form: 'fixed', parsed: true }
  const unit = rule.redPackageAmountUnit
  if (unit === '折') return { form: 'ratio', parsed: true }
  if (unit === '价') return { form: 'price', parsed: true }
  if (unit === '件折') return { form: 'nth', parsed: parseNth(rule.redPackageRangeAmount) !== null }

  // 写平面只接受 null/元/折/价/件折。未知 unit 不能参与其它启发式判别，否则会把拼错的单位
  // 伪装成一个看似健康的 random/ladder。
  if (unit != null && unit !== '元') return { form: 'fixed', parsed: false }

  if (rule.redPackageTakeType === 2) {
    return { form: 'random', parsed: parseRandomRange(rule.redPackageRangeAmount) !== null }
  }

  const range = rule.redPackageRangeAmount?.trim()
  if (!range) return { form: 'fixed', parsed: true }
  try {
    const raw = JSON.parse(range) as unknown
    // [] 不表达任何阶梯，因此仍是固定金额；非空数组即归阶梯所有，哪怕其中档位已损坏。
    if (Array.isArray(raw)) {
      if (raw.length === 0) return { form: 'fixed', parsed: true }
      return { form: 'ladder', parsed: cleanLadder(parseLadder(range)).length > 0 }
    }
  } catch {
    // 非空但不可解析的历史值仍由 ladder 认领，防止一次编辑把原值悄悄清空。
  }
  return { form: 'ladder', parsed: false }
}

/** 后端 rule → 编辑器权益草稿。所有调用方共用，禁止在页面里重写判别链。 */
export function benefitDraftFromRule(rule: BenefitRuleLike): BenefitDraftFields & { parsed: boolean } {
  const shape = benefitFormOf(rule)
  const draft: BenefitDraftFields & { parsed: boolean } = {
    redMode: shape.form,
    amount: '',
    maxDiscount: '',
    rangeMin: '',
    rangeMax: '',
    nth: '',
    ladder: [],
    parsed: shape.parsed,
  }
  // ladder 也要回填 amount：「阶梯 + 底价」是合法且被后端金标覆盖的组合
  // （DecisionGoldenSetTest「订单金额缺失 → 阶梯不参与，退回固定金额」期望减 7）。
  // 漏了它，一次零改动的编辑就会把 redPackageAmount 提交成 null，
  // 把这张券从「恒减底价」悄悄变成「按档位发」，缺 orderAmount 时更是从减 7 变成 0 元候选。
  if (shape.form === 'fixed' || shape.form === 'ratio' || shape.form === 'price'
      || shape.form === 'nth' || shape.form === 'ladder') {
    draft.amount = rule.redPackageAmount ?? ''
  }
  if (shape.form === 'ratio') draft.maxDiscount = rule.redPackageMaxDiscount ?? ''
  if (shape.form === 'nth') draft.nth = parseNth(rule.redPackageRangeAmount) ?? ''
  if (shape.form === 'random') {
    const range = parseRandomRange(rule.redPackageRangeAmount)
    draft.rangeMin = range?.min ?? ''
    draft.rangeMax = range?.max ?? ''
  }
  if (shape.form === 'ladder') draft.ladder = parseLadder(rule.redPackageRangeAmount || '')
  return draft
}

/** 编辑器权益草稿 → create 请求字段。`enabled=false` 用于买赠/加价购，清空全部红包字段。 */
export function benefitRequestFields(draft: BenefitDraftFields, enabled = true): BenefitRequestFields {
  if (!enabled) {
    return {
      redPackageTakeType: null,
      redPackageAmount: null,
      redPackageAmountUnit: '元',
      redPackageMaxDiscount: null,
      redPackageRangeAmount: null,
    }
  }

  const form = draft.redMode
  return {
    redPackageTakeType: form === 'fixed' ? 1 : form === 'random' ? 2 : null,
    // ladder 一并带出 amount（底价）：新建阶梯时 draft.amount 为空 → numOrNull('') = null，行为不变；
    // 编辑既有「阶梯 + 底价」时原值得以保留，不会被静默清成 null。与 benefitDraftFromRule 成对，改一个必须改另一个。
    redPackageAmount: form === 'fixed' || form === 'ratio' || form === 'price'
      || form === 'nth' || form === 'ladder'
      ? numOrNull(draft.amount) : null,
    redPackageAmountUnit: form === 'ratio' ? '折' : form === 'price' ? '价' : form === 'nth' ? '件折' : '元',
    redPackageMaxDiscount: form === 'ratio' ? numOrNull(draft.maxDiscount) : null,
    redPackageRangeAmount: form === 'ladder'
      ? JSON.stringify(cleanLadder(draft.ladder))
      : form === 'random'
        ? JSON.stringify({ min: numOrNull(draft.rangeMin), max: numOrNull(draft.rangeMax) })
        : form === 'nth'
          ? JSON.stringify({ nth: numOrNull(draft.nth) })
          : null,
  }
}

// ---- 时间转换（平移 toEpoch/toLocalInput/isoToLocal）----
export function toEpoch(local: string): number | null {
  return local ? new Date(local).getTime() : null
}
export function toLocalInput(ms: number | null): string {
  if (ms == null) return ''
  const d = new Date(ms)
  const p = (n: number) => (n < 10 ? '0' : '') + n
  return (
    d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
    'T' + p(d.getHours()) + ':' + p(d.getMinutes())
  )
}
export function isoToLocal(iso: string | number | null): string {
  if (!iso) return ''
  const ms = new Date(iso).getTime()
  return isNaN(ms) ? '' : toLocalInput(ms)
}

export function numOrNull(v: unknown): number | null {
  return v === '' || v == null ? null : Number(v)
}
export function uuid(): string {
  return 'req-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8)
}
export function splitNums(s: string): number[] {
  return (s || '').split(',').map((x) => x.trim()).filter((x) => x !== '').map(Number)
}
export function splitStrs(s: string): string[] {
  return (s || '').split(',').map((x) => x.trim()).filter((x) => x !== '')
}

// ---- 字典查询辅助 ----
export function operandOf(op: string, operators: DictOperator[]): 'SCALAR' | 'RANGE' | 'LIST' {
  const o = operators.find((x) => x.code === op)
  return o ? o.operand : 'SCALAR'
}
export function fieldByKey(key: string, fields: DictField[]): DictField | undefined {
  return fields.find((f) => f.key === key)
}

/** 按 operand 造空值（RANGE=2 空 / LIST=[] / SCALAR=''） */
export function emptyValue(operand: 'SCALAR' | 'RANGE' | 'LIST'): string | string[] {
  return operand === 'RANGE' ? ['', ''] : operand === 'LIST' ? [] : ''
}

/** 新建一个空叶子（首字段 + 其首运算符 + 对应空值） */
export function emptyLeaf(fields: DictField[], operators: DictOperator[]): LeafNode {
  const f = fields[0]
  const op = (f?.operators || [])[0] || ''
  return { id: nodeId(), field: f?.key || '', op, value: emptyValue(operandOf(op, operators)) }
}

export function emptyGroup(): GroupNode {
  return { id: nodeId(), logic: 'AND', children: [] }
}

// ---- 前端就地校验（新增，激活既有 .field-error 语义）----
export interface ValidationError {
  path: string
  msg: string
}

/** 单叶校验原因：无效返回一句人话，有效返回 ''（供条件树逐叶行内定位，纯函数可单测）。 */
export function leafErrorReason(leaf: LeafNode, operators: DictOperator[]): string {
  if (!leaf.field) return '未选字段'
  if (!leaf.op) return '未选运算符'
  const operand = operandOf(leaf.op, operators)
  if (operand === 'RANGE') {
    const v = Array.isArray(leaf.value) ? leaf.value : ['', '']
    if (v[0] === '' || v[1] === '') return '区间需填上下界'
  } else if (operand === 'LIST') {
    if (!Array.isArray(leaf.value) || leaf.value.length === 0) return '列表需至少一个值'
  } else if (leaf.value === '' || leaf.value == null) {
    return '需填值'
  }
  return ''
}

/** 遍历原始树（保留 id），收集无效叶子的 id→原因，供 EditorView 提交后逐叶显红。空组不计（提交时自动剪除=恒通过）。 */
export function invalidLeafReasons(node: ConditionNode | null, operators: DictOperator[]): Map<string, string> {
  const map = new Map<string, string>()
  function walk(n: ConditionNode): void {
    if (isGroup(n)) {
      n.children.forEach(walk)
      return
    }
    const leaf = n as LeafNode
    const reason = leafErrorReason(leaf, operators)
    if (reason && leaf.id) map.set(leaf.id, reason)
  }
  if (node) walk(node)
  return map
}

/** 条件树值校验：RANGE 需 2 非空、LIST 需非空、SCALAR 需非空（对齐后端白名单，先于 /preview 挡） */
export function validateTree(node: ConditionNode | null, operators: DictOperator[], path = 'root'): ValidationError[] {
  if (!node) return []
  if (isGroup(node)) {
    return node.children.flatMap((c, i) => validateTree(c, operators, `${path}.${i}`))
  }
  const leaf = node as LeafNode
  const operand = operandOf(leaf.op, operators)
  const errs: ValidationError[] = []
  if (!leaf.field) errs.push({ path, msg: '未选字段' })
  if (!leaf.op) errs.push({ path, msg: '未选运算符' })
  if (operand === 'RANGE') {
    const v = Array.isArray(leaf.value) ? leaf.value : ['', '']
    if (v[0] === '' || v[1] === '') errs.push({ path, msg: '区间需填上下界' })
  } else if (operand === 'LIST') {
    if (!Array.isArray(leaf.value) || leaf.value.length === 0) errs.push({ path, msg: '列表需至少一个值' })
  } else {
    if (leaf.value === '' || leaf.value == null) errs.push({ path, msg: '需填值' })
  }
  return errs
}
