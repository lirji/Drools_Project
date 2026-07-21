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
    const arr = JSON.parse(json) as Array<{ min?: number; max?: number; reward?: number }>
    return (arr || []).map((t) => ({
      min: t.min == null ? 0 : t.min,
      max: t.max == null ? '' : t.max,
      reward: t.reward == null ? '' : t.reward,
    }))
  } catch {
    return []
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
