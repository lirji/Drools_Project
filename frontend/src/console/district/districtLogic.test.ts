import { describe, it, expect } from 'vitest'
import {
  parseCodes, toCsv, buildIndex, pathOf, isKnown, labelOf, search,
  addCode, removeCode, budgetOf, childrenOf, MAX_DISTRICTS, DISTRICT_IDS_MAX_LEN,
} from './districtLogic'
import { pruneTree, stripDistrictNodes, SOURCE_DISTRICT } from '../logic'
import type { ConditionNode, District } from '@/shared/types'

const d = (code: string, name: string, level: 1 | 2 | 3, parent: string | null,
           shortName = name, pinyin = '', pinyinInitial = ''): District =>
  ({ code, name, shortName, level, parent, pinyin, pinyinInitial })

// 夹具刻意包含两种形态：广东（省→市→区，三层）与北京（省→区，**两层**）。
// 117 个真实行政区就是后一种，级联绝不能假设永远三层。
const FIXTURE: District[] = [
  d('440000', '广东省', 1, null, '广东', 'guangdong', 'g'),
  d('440300', '深圳市', 2, '440000', '深圳', 'shenzhen', 's'),
  d('440305', '南山区', 3, '440300', '南山', 'nanshan', 'n'),
  d('440304', '福田区', 3, '440300', '福田', 'futian', 'f'),
  d('110000', '北京市', 1, null, '北京', 'beijing', 'b'),
  d('110101', '东城区', 3, '110000', '东城', 'dongcheng', 'd'),
]
const IDX = buildIndex(FIXTURE)

describe('CSV 互转', () => {
  it('空值一律得空数组', () => {
    expect(parseCodes(null)).toEqual([])
    expect(parseCodes('')).toEqual([])
    expect(parseCodes('   ')).toEqual([])
  })

  it('去空白、去空段、去重，但**不排序**', () => {
    // 顺序即契约：打开编辑器不做任何修改直接保存，出去的串必须与进来的一致。
    expect(parseCodes(' 440305 , ,110101,440305 ')).toEqual(['440305', '110101'])
    expect(toCsv(['440305', '110101'])).toBe('440305,110101')
  })

  it('往返无尾逗号', () => {
    expect(toCsv(parseCodes('440305,110101'))).toBe('440305,110101')
  })
})

describe('层级与路径', () => {
  it('三层与两层都能拼出完整路径', () => {
    expect(pathOf(IDX, '440305')).toBe('广东省/深圳市/南山区')
    expect(pathOf(IDX, '110101')).toBe('北京市/东城区') // 直辖市没有地市级这一层
    expect(pathOf(IDX, '440000')).toBe('广东省')
  })

  it('字典里没有的码回退成裸码，绝不返回空串', () => {
    // 500105 江北区 2025-11 已撤销、民政部废止代码，字典里查不到——但存量活动里可能有。
    expect(pathOf(IDX, '500105')).toBe('500105')
    expect(labelOf(IDX, '500105')).toBe('500105')
    expect(isKnown(IDX, '500105')).toBe(false)
    expect(isKnown(IDX, '440305')).toBe(true)
  })

  it('下级查询：直辖市的下级直接是区县级', () => {
    expect(childrenOf(IDX, '440000').map((x) => x.code)).toEqual(['440300'])
    expect(childrenOf(IDX, '440300').map((x) => x.code)).toEqual(['440305', '440304'])
    expect(childrenOf(IDX, '110000').map((x) => x.code)).toEqual(['110101'])
    expect(childrenOf(IDX, '440305')).toEqual([]) // 区县级没有下级
    expect(childrenOf(IDX, null).map((x) => x.code)).toEqual(['440000', '110000'])
  })
})

describe('搜索', () => {
  it('全称 / 简称 / 全拼 / 首字母 / 代码都能命中', () => {
    expect(search(FIXTURE, '南山').map((x) => x.code)).toEqual(['440305'])
    expect(search(FIXTURE, 'nanshan').map((x) => x.code)).toEqual(['440305'])
    expect(search(FIXTURE, '4403').map((x) => x.code)).toEqual(['440300', '440305', '440304'])
    expect(search(FIXTURE, '').map((x) => x.code)).toEqual([])
  })

  it('结果截断在 limit —— 搜「区」会命中上千条，一次全渲染在手机上必掉帧', () => {
    const many = Array.from({ length: 200 }, (_, i) => d(String(500000 + i), `第${i}区`, 3, null))
    expect(search(many, '区', 50)).toHaveLength(50)
  })
})

describe('选择：祖先/后代互斥', () => {
  it('选了省，再选它下面的市会被忽略（后端展开时本来就包含）', () => {
    const a = addCode(IDX, [], '440000')
    expect(a).toEqual(['440000'])
    expect(addCode(IDX, a, '440300')).toBe(a) // 返回原数组，调用方据此提示
    expect(addCode(IDX, a, '440305')).toBe(a) // 隔一层的后代同样被吸收
  })

  it('先选了区，再选它所属的省 → 区被摘掉，只留省', () => {
    const a = addCode(IDX, ['440305', '110101'], '440000')
    expect(a).toEqual(['110101', '440000'])
  })

  it('重复添加是幂等的；删除按码删', () => {
    expect(addCode(IDX, ['440305'], '440305')).toEqual(['440305'])
    expect(removeCode(['440305', '110101'], '440305')).toEqual(['110101'])
  })

  it('字典外的码不会被误吸收（没有祖先信息就只按自身处理）', () => {
    const a = addCode(IDX, ['500105'], '440000')
    expect(a).toEqual(['500105', '440000'])
  })
})

describe('列宽预算', () => {
  it('上限来自 varchar(1024)：146 个码正好 1021 字符，147 个就越界', () => {
    expect(MAX_DISTRICTS).toBe(146)
    const at = Array.from({ length: MAX_DISTRICTS }, (_, i) => String(100000 + i))
    expect(toCsv(at).length).toBe(1021)
    expect(toCsv(at).length).toBeLessThanOrEqual(DISTRICT_IDS_MAX_LEN)
    expect(budgetOf(at).full).toBe(true)
    expect(budgetOf(at).remaining).toBe(0)

    const over = [...at, '999999']
    expect(toCsv(over).length).toBe(1028)
    expect(toCsv(over).length).toBeGreaterThan(DISTRICT_IDS_MAX_LEN)
  })

  it('未满时 remaining 为正', () => {
    expect(budgetOf(['440305']).remaining).toBe(145)
    expect(budgetOf(['440305']).full).toBe(false)
  })
})

/**
 * 写平面保存时会把「投放地域」翻译成一片 `userDistrictId IN (自身+全部后代)` 注入条件树，
 * 并标 `source:"district"`。这个标记是**后端做幂等合成的唯一依据**，前端有两处必须配合：
 * `pruneTree` 提交时不能剥掉它，`loadForEdit` 回读时要把它剥出 UI。
 */
describe('地域注入节点的剥离与保真', () => {
  const userLeaf = { field: 'userLevel', op: '>=', value: '3' }
  const distLeaf = { field: 'userDistrictId', op: 'IN', value: ['440000', '440300'], source: SOURCE_DISTRICT }

  it('pruneTree 剥 id 但**保留 source**——两者看着都多余，性质相反', () => {
    const tree: ConditionNode = {
      id: 'n1', logic: 'AND',
      children: [{ id: 'n2', ...userLeaf }, { id: 'n3', ...distLeaf }],
    } as ConditionNode
    const out = pruneTree(tree) as any
    expect(out.id).toBeUndefined()
    expect(out.children[0].id).toBeUndefined()
    expect(out.children[0].source).toBeUndefined() // 用户手写的没有 source，也不该凭空长一个
    // 剥掉 source，后端就认不出这是自己上次注入的，会把它当用户条件保留再叠一条新的：
    // 「广东改北京」于是变成 IN(广东…) AND IN(北京…) —— 恒不命中、静默停发。
    expect(out.children[1].source).toBe(SOURCE_DISTRICT)
  })

  it('剥掉并进 AND 组的那片地域叶子，用户条件原样留下', () => {
    const tree = { logic: 'AND', children: [userLeaf, distLeaf] } as ConditionNode
    const out = stripDistrictNodes(tree) as any
    expect(out.children).toHaveLength(1)
    expect(out.children[0]).toEqual(userLeaf)
  })

  it('用户树是 OR 组时后端会包一层 AND —— 剥离要还原成里面那棵，而不是把 OR 也吞掉', () => {
    const orTree = { logic: 'OR', children: [userLeaf, { field: 'tag', op: 'eq', value: 'vip' }] }
    const wrapped = { logic: 'AND', children: [orTree, distLeaf], source: SOURCE_DISTRICT } as ConditionNode
    expect(stripDistrictNodes(wrapped)).toEqual(orTree)
  })

  it('「只投广东、不配其它条件」→ 整棵树都是注入的，剥完得 null（回到空条件树）', () => {
    const tree = { logic: 'AND', children: [distLeaf] } as ConditionNode
    expect(stripDistrictNodes(tree)).toBeNull()
  })

  it('没有注入节点时是恒等变换，且不 mutate 入参', () => {
    const tree = { logic: 'AND', children: [userLeaf] } as ConditionNode
    const snapshot = JSON.stringify(tree)
    expect(stripDistrictNodes(tree)).toEqual(tree)
    expect(JSON.stringify(tree)).toBe(snapshot)
  })

  /**
   * 后端存 `condition_tree_json` 用的是零配置 `new ObjectMapper()`（`JsonInclude.ALWAYS`），
   * 叶子节点因此带着 `"logic": null` 落库。前端 `isGroup` 若写成 `logic !== undefined`，
   * 每一片叶子都会被判成分组 → `children` 是 `null` → `.map` 抛 TypeError →
   * 被 `loadForEdit` 的 catch 吞掉 → **存量活动的资格条件树整棵消失，再保存就真的没了**。
   *
   * 所以这条用例刻意用后端**真实写出来的**那种形状，而不是手写的干净 JSON。
   */
  it('后端真实形状（叶子带 logic:null / children:null）不会被判成分组', () => {
    const realShape = {
      logic: 'AND',
      children: [
        { logic: null, children: null, field: 'userLevel', op: '>=', value: '3', source: null },
        { logic: null, children: null, field: 'userDistrictId', op: 'IN', value: ['440000'], source: SOURCE_DISTRICT },
      ],
      field: null, op: null, value: null, source: null,
    } as unknown as ConditionNode

    const out = stripDistrictNodes(realShape) as any // 判别写错时这一行直接 TypeError
    expect(out.children).toHaveLength(1)
    expect(out.children[0].field).toBe('userLevel')
    expect(JSON.stringify(pruneTree(out))).not.toContain('userDistrictId')
  })

  it('空串 logic 也算叶子（后端 isGroup 是 !logic.isBlank()，两侧必须同语义）', () => {
    const leaf = { logic: '', children: null, field: 'userLevel', op: '>=', value: '3' } as unknown as ConditionNode
    expect(stripDistrictNodes(leaf)).toEqual(leaf)
  })

  it('剥离 → 提交 → 后端再注入，往返一次不增不减（幂等）', () => {
    // 模拟第二次保存：库里存的是合成后的树，剥出用户树，pruneTree 后提交。
    const stored = { logic: 'AND', children: [userLeaf, distLeaf] } as ConditionNode
    const submitted = pruneTree(stripDistrictNodes(stored)) as any
    expect(submitted.children).toHaveLength(1)
    expect(JSON.stringify(submitted)).not.toContain('userDistrictId')
  })
})
