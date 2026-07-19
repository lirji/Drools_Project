import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ConditionGroup from './ConditionGroup.vue'
import type { DictField, DictOperator, GroupNode, LeafNode } from '@/shared/types'
import { nodeId } from '../logic'

const FIELDS: DictField[] = [
  { key: 'age', label: '年龄', valueType: 'NUMBER', operators: ['eq', 'between'], enumValues: null },
  { key: 'tag', label: '标签', valueType: 'STRING', operators: ['in'], enumValues: null },
]
const OPS: DictOperator[] = [
  { code: 'eq', label: '等于', operand: 'SCALAR' },
  { code: 'between', label: '介于', operand: 'RANGE' },
  { code: 'in', label: '属于', operand: 'LIST' },
]

function leaf(field: string, value: string): LeafNode {
  return { id: nodeId(), field, op: 'eq', value }
}

describe('ConditionGroup（递归 + 稳定 key）', () => {
  it('删中间行后剩余行的值不串（稳定 node.id 作 key）', async () => {
    const node: GroupNode = {
      id: nodeId(), logic: 'AND',
      children: [leaf('age', 'A'), leaf('age', 'B'), leaf('age', 'C')],
    }
    const wrapper = mount(ConditionGroup, {
      props: { node, fields: FIELDS, operators: OPS, depth: 0, root: true },
    })
    // 删中间行（index 1 = 'B'）
    const dels = wrapper.findAll('[data-testid="leaf-del"]')
    expect(dels.length).toBe(3)
    await dels[1].trigger('click')
    // 剩 A、C，且值未错位
    expect(node.children.length).toBe(2)
    expect((node.children[0] as LeafNode).value).toBe('A')
    expect((node.children[1] as LeafNode).value).toBe('C')
    // DOM 上标量输入框的值也应是 A、C（keyed 复用，不串值）
    const inputs = wrapper.findAll('[data-testid="scalar-val"]')
    expect((inputs[0].element as HTMLInputElement).value).toBe('A')
    expect((inputs[1].element as HTMLInputElement).value).toBe('C')
  })

  it('+条件 / +分组，深度上限禁用', async () => {
    const node: GroupNode = { id: nodeId(), logic: 'AND', children: [] }
    const wrapper = mount(ConditionGroup, {
      props: { node, fields: FIELDS, operators: OPS, depth: 4, root: true }, // 已达上限
    })
    await wrapper.find('[data-testid="add-cond"]').trigger('click')
    expect(node.children.length).toBe(1) // 条件可加
    const addGroup = wrapper.find('[data-testid="add-group"]')
    expect((addGroup.element as HTMLButtonElement).disabled).toBe(true) // depth>=4 禁用
  })

  it('AND/OR 切换写入 node.logic', async () => {
    const node: GroupNode = { id: nodeId(), logic: 'AND', children: [] }
    const wrapper = mount(ConditionGroup, {
      props: { node, fields: FIELDS, operators: OPS, depth: 0, root: true },
    })
    await wrapper.find('[data-testid="logic-OR"]').trigger('click')
    expect(node.logic).toBe('OR')
  })
})
