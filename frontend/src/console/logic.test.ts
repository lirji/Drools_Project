import { describe, it, expect } from 'vitest'
import {
  pruneTree, cleanLadder, parseLadder, parseNth, parseRandomRange, validateTree, emptyValue, operandOf,
  toEpoch, isoToLocal, splitNums, splitStrs, assignIds, nodeId,
  invalidLeafReasons, leafErrorReason, benefitFormOf,
} from './logic'
import type { ConditionNode, DictOperator, GroupNode, LeafNode } from '@/shared/types'

const OPS: DictOperator[] = [
  { code: 'eq', label: '等于', operand: 'SCALAR' },
  { code: 'between', label: '介于', operand: 'RANGE' },
  { code: 'in', label: '属于', operand: 'LIST' },
]

describe('pruneTree', () => {
  it('空组剪成 null（恒通过）', () => {
    expect(pruneTree({ logic: 'AND', children: [] })).toBeNull()
    expect(pruneTree(null)).toBeNull()
  })

  it('剥离临时 id', () => {
    const tree: GroupNode = {
      id: 'g1', logic: 'AND',
      children: [{ id: 'l1', field: 'age', op: 'eq', value: '18' }],
    }
    const out = pruneTree(tree) as GroupNode
    expect(out.id).toBeUndefined()
    expect((out.children[0] as LeafNode).id).toBeUndefined()
    expect((out.children[0] as LeafNode).field).toBe('age')
  })

  it('递归剪除嵌套空组，保留非空', () => {
    const tree: GroupNode = {
      logic: 'AND',
      children: [
        { logic: 'OR', children: [] }, // 空 → 剪
        { field: 'age', op: 'eq', value: '18' },
        { logic: 'AND', children: [{ field: 'vip', op: 'eq', value: '1' }] },
      ],
    }
    const out = pruneTree(tree) as GroupNode
    expect(out.children.length).toBe(2) // 空 OR 被剪
  })

  it('不 mutate 入参', () => {
    const tree: GroupNode = { id: 'g', logic: 'AND', children: [{ id: 'l', field: 'a', op: 'eq', value: '1' }] }
    pruneTree(tree)
    expect(tree.id).toBe('g') // 原树 id 仍在
  })
})

describe('cleanLadder / parseLadder', () => {
  it('cleanLadder 只留有 reward 的行，归一 min/max', () => {
    const out = cleanLadder([
      { min: '', max: '', reward: '10' },
      { min: '100', max: '200', reward: '20' },
      { min: '5', max: '', reward: '' }, // 无 reward → 剔
    ])
    expect(out).toEqual([
      { min: 0, max: null, reward: 10 },
      { min: 100, max: 200, reward: 20 },
    ])
  })
  it('parseLadder 往返', () => {
    const json = JSON.stringify([{ min: 0, max: null, reward: 10 }])
    const rows = parseLadder(json)
    expect(rows[0]).toEqual({ min: 0, max: '', reward: 10 })
  })
  it('parseLadder 容错非法 JSON', () => {
    expect(parseLadder('not json')).toEqual([])
  })
})

describe('validateTree', () => {
  it('SCALAR 空值报错', () => {
    const t: ConditionNode = { logic: 'AND', children: [{ field: 'age', op: 'eq', value: '' }] }
    expect(validateTree(t, OPS).length).toBe(1)
  })
  it('RANGE 需 2 非空', () => {
    const t: ConditionNode = { logic: 'AND', children: [{ field: 'age', op: 'between', value: ['1', ''] }] }
    expect(validateTree(t, OPS).length).toBe(1)
    const ok: ConditionNode = { logic: 'AND', children: [{ field: 'age', op: 'between', value: ['1', '9'] }] }
    expect(validateTree(ok, OPS).length).toBe(0)
  })
  it('LIST 需非空', () => {
    const t: ConditionNode = { logic: 'AND', children: [{ field: 'tag', op: 'in', value: [] }] }
    expect(validateTree(t, OPS).length).toBe(1)
  })
  it('空树无错', () => {
    expect(validateTree(null, OPS)).toEqual([])
  })
})

describe('leafErrorReason / invalidLeafReasons（逐叶行内定位）', () => {
  it('leafErrorReason 各 operand 的原因文案', () => {
    expect(leafErrorReason({ field: '', op: 'eq', value: '' }, OPS)).toBe('未选字段')
    expect(leafErrorReason({ field: 'age', op: '', value: '' }, OPS)).toBe('未选运算符')
    expect(leafErrorReason({ field: 'age', op: 'eq', value: '' }, OPS)).toBe('需填值')
    expect(leafErrorReason({ field: 'age', op: 'between', value: ['1', ''] }, OPS)).toBe('区间需填上下界')
    expect(leafErrorReason({ field: 'tag', op: 'in', value: [] }, OPS)).toBe('列表需至少一个值')
    expect(leafErrorReason({ field: 'age', op: 'eq', value: '18' }, OPS)).toBe('')
  })

  it('invalidLeafReasons 收集无效叶子 id→原因，有效不计', () => {
    const bad: LeafNode = { id: 'L1', field: 'age', op: 'eq', value: '' }
    const good: LeafNode = { id: 'L2', field: 'age', op: 'eq', value: '18' }
    const tree: GroupNode = {
      id: 'g', logic: 'AND',
      children: [bad, good, { id: 'g2', logic: 'OR', children: [{ id: 'L3', field: 'tag', op: 'in', value: [] }] }],
    }
    const m = invalidLeafReasons(tree, OPS)
    expect(m.get('L1')).toBe('需填值')
    expect(m.get('L3')).toBe('列表需至少一个值')
    expect(m.has('L2')).toBe(false)
    expect(m.size).toBe(2)
  })

  it('空树返回空 Map', () => {
    expect(invalidLeafReasons(null, OPS).size).toBe(0)
  })
})

describe('operandOf / emptyValue', () => {
  it('operand 分发', () => {
    expect(operandOf('between', OPS)).toBe('RANGE')
    expect(operandOf('in', OPS)).toBe('LIST')
    expect(operandOf('unknown', OPS)).toBe('SCALAR')
  })
  it('emptyValue 按 operand', () => {
    expect(emptyValue('RANGE')).toEqual(['', ''])
    expect(emptyValue('LIST')).toEqual([])
    expect(emptyValue('SCALAR')).toBe('')
  })
})

describe('时间与拆分', () => {
  it('toEpoch/isoToLocal', () => {
    expect(toEpoch('')).toBeNull()
    const ms = toEpoch('2026-01-01T00:00')
    expect(typeof ms).toBe('number')
    expect(isoToLocal(null)).toBe('')
  })
  it('splitNums/splitStrs 去空白去空', () => {
    expect(splitNums(' 1, 2 ,,3 ')).toEqual([1, 2, 3])
    expect(splitStrs('a, ,b')).toEqual(['a', 'b'])
  })
})

describe('parseNth（第 N 件折的 N）', () => {
  it('对象形态的 nth 读出来，N≥2', () => {
    expect(parseNth('{"nth":2}')).toBe(2)
    expect(parseNth('{"nth":3}')).toBe(3)
  })
  it('N<2 / 缺字段 / 非整数一律 null——绝不回落成默认值', () => {
    // N=1 等于全场打折（那是折扣型），配成 1 更像配错，宁可让必填校验拦住
    expect(parseNth('{"nth":1}')).toBeNull()
    expect(parseNth('{"nth":0}')).toBeNull()
    expect(parseNth('{"nth":2.5}')).toBeNull()
    expect(parseNth('{}')).toBeNull()
  })
  it('数组归阶梯管，不许两条解析路径抢同一份数据', () => {
    expect(parseNth('[{"min":0,"max":100,"reward":5}]')).toBeNull()
    // 反向也要成立：阶梯解析器看到 nth 对象返回空表（与后端 LadderRangeParser 同规矩）
    expect(parseLadder('{"nth":2}')).toEqual([])
  })
  it('空 / 脏 JSON 不抛异常', () => {
    expect(parseNth(null)).toBeNull()
    expect(parseNth('')).toBeNull()
    expect(parseNth('不是 JSON')).toBeNull()
  })
})

describe('parseRandomRange（随机红包区间）', () => {
  it('读出闭区间 [min, max]', () => {
    expect(parseRandomRange('{"min":5,"max":20}')).toEqual({ min: 5, max: 20 })
    expect(parseRandomRange('{"min":0,"max":0}')).toEqual({ min: 0, max: 0 })
  })
  it('min>max / 负数 / 缺字段一律 null（与后端 RandomRangeParser 同规矩）', () => {
    expect(parseRandomRange('{"min":20,"max":5}')).toBeNull()
    expect(parseRandomRange('{"min":-1,"max":5}')).toBeNull()
    expect(parseRandomRange('{"min":5}')).toBeNull()
  })
  it('数组归阶梯管；空 / 脏 JSON 不抛异常', () => {
    expect(parseRandomRange('[{"min":5,"max":20}]')).toBeNull()
    expect(parseRandomRange(null)).toBeNull()
    expect(parseRandomRange('不是 JSON')).toBeNull()
  })
})

describe('benefitFormOf（全前端唯一形态判别）', () => {
  it.each([
    ['无 rule', null, 'fixed', true],
    ['固定金额', { redPackageAmountUnit: '元', redPackageTakeType: 1 }, 'fixed', true],
    ['随机金额', { redPackageAmountUnit: '元', redPackageTakeType: 2, redPackageRangeAmount: '{"min":5,"max":20}' }, 'random', true],
    ['阶梯分档', { redPackageAmountUnit: '元', redPackageRangeAmount: '[{"min":0,"max":100,"reward":5}]' }, 'ladder', true],
    ['折扣', { redPackageAmountUnit: '折' }, 'ratio', true],
    ['一口价', { redPackageAmountUnit: '价' }, 'price', true],
    ['第 N 件折', { redPackageAmountUnit: '件折', redPackageRangeAmount: '{"nth":3}' }, 'nth', true],
    ['省略元单位的历史固定金额', { redPackageAmountUnit: null, redPackageTakeType: 1 }, 'fixed', true],
  ] as const)('%s → %s', (_name, rule, form, parsed) => {
    expect(benefitFormOf(rule)).toEqual({ form, parsed })
  })

  it('未知 unit 回落 fixed，但标记为不可解析，不能伪装成健康数据', () => {
    expect(benefitFormOf({ redPackageAmountUnit: '摺', redPackageTakeType: 2, redPackageRangeAmount: '{"min":1,"max":2}' }))
      .toEqual({ form: 'fixed', parsed: false })
    expect(benefitFormOf({ redPackageAmountUnit: '', redPackageTakeType: 2, redPackageRangeAmount: '{"min":1,"max":2}' }))
      .toEqual({ form: 'fixed', parsed: false })
  })

  it('takeType=2 即归 random 所有；坏 JSON 与 min>max 只改变 parsed，不改变归属', () => {
    expect(benefitFormOf({ redPackageAmountUnit: '元', redPackageTakeType: 2, redPackageRangeAmount: '坏 JSON' }))
      .toEqual({ form: 'random', parsed: false })
    expect(benefitFormOf({ redPackageAmountUnit: '元', redPackageTakeType: 2, redPackageRangeAmount: '{"min":5,"max":1}' }))
      .toEqual({ form: 'random', parsed: false })
  })

  it('空数组不表达阶梯，回落 fixed', () => {
    expect(benefitFormOf({ redPackageAmountUnit: '元', redPackageRangeAmount: '[]' }))
      .toEqual({ form: 'fixed', parsed: true })
  })

  it('非空但损坏的阶梯仍归 ladder 所有，防止编辑保存时静默抹列', () => {
    expect(benefitFormOf({ redPackageAmountUnit: '元', redPackageRangeAmount: '[{"min":0}]' }))
      .toEqual({ form: 'ladder', parsed: false })
  })
})

describe('nodeId / assignIds', () => {
  it('nodeId 唯一', () => {
    const a = nodeId(), b = nodeId()
    expect(a).not.toBe(b)
  })
  it('assignIds 递归补 id 且稳定（幂等不覆盖已有）', () => {
    const tree: GroupNode = { logic: 'AND', children: [{ field: 'a', op: 'eq', value: '1' }] }
    assignIds(tree)
    const rootId = tree.id
    const leafId = (tree.children[0] as LeafNode).id
    expect(rootId).toBeDefined()
    expect(leafId).toBeDefined()
    assignIds(tree) // 再跑一次不应改
    expect(tree.id).toBe(rootId)
    expect((tree.children[0] as LeafNode).id).toBe(leafId)
  })
})
