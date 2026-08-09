import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { h, reactive } from 'vue'
import DynRowTable from './DynRowTable.vue'

/**
 * 与 condition-tree/ConditionGroup.test.ts 的「稳定 key」用例同型。
 *
 * 背景：本组件此前用 `:key="i"`，而 logic.ts:6 早就为条件树写下「禁 index 作 key（删中间行会串值——
 * 30 号决策已实证）」。同一个坑在这里遗留着，且阶梯档 / 赠品 / SPU 绑定 / 商品池四处都在用它。
 *
 * 判别方法：slot 里放一个**未受控** input——
 *   - `data-row` 由 Vue 从数据 patch（代表"这一行现在该显示谁"）
 *   - `.value` 只由测试直接写 DOM（代表"用户已输入但未提交"的行内状态），Vue 不碰它
 * 删中间行后，若 key 是 index，Vue 会复用后一行的 DOM 节点：`data-row` 被 patch 成新数据，
 * 而 `.value` 仍是旧节点的 → 二者对不上 = 串值。稳定 key 下两者必须同时正确。
 */
type Row = { v: string }

function mountTable(rows: Row[]) {
  return mount(DynRowTable, {
    props: {
      rows,
      headers: ['值'],
      makeRow: (): Row => ({ v: '' }),
      label: '档',
    },
    slots: {
      default: (p: { row: unknown; index: number }) =>
        h('input', { class: 'cell', 'data-row': (p.row as Row).v }),
    },
  })
}

const cells = (w: ReturnType<typeof mountTable>) =>
  w.findAll('input.cell').map((c) => c.element as HTMLInputElement)

describe('DynRowTable（稳定行 key）', () => {
  it('删中间行后，剩余行的未受控行内状态不串位', async () => {
    const rows = reactive<Row[]>([{ v: 'A' }, { v: 'B' }, { v: 'C' }])
    const wrapper = mountTable(rows)

    expect(cells(wrapper)).toHaveLength(3)

    // 模拟用户在三行里各自输入（只写 DOM value，不回写数据 → 未受控状态）
    cells(wrapper).forEach((el) => {
      el.value = 'typed-' + el.dataset.row
    })

    // 删中间行 B（删除按钮带 aria-label，不会误选到底部的"添加"按钮）
    const dels = wrapper.findAll('button[aria-label]')
    expect(dels).toHaveLength(3)
    await dels[1].trigger('click')

    // 数据层：剩 A、C
    expect(rows.map((r) => r.v)).toEqual(['A', 'C'])

    // DOM 层：数据与未受控状态必须成对正确
    const after = cells(wrapper)
    expect(after).toHaveLength(2)
    expect(after.map((el) => el.dataset.row)).toEqual(['A', 'C'])
    expect(after.map((el) => el.value)).toEqual(['typed-A', 'typed-C'])
  })

  it('追加新行时，既有行的 DOM 节点被原样复用', async () => {
    const rows = reactive<Row[]>([{ v: 'A' }, { v: 'B' }])
    const wrapper = mountTable(rows)

    cells(wrapper)[0].value = 'typed-A'
    const firstEl = cells(wrapper)[0]

    await wrapper.find('[data-testid="dyn-add"]').trigger('click')
    expect(rows).toHaveLength(3)

    const after = cells(wrapper)
    expect(after).toHaveLength(3)
    expect(after[0]).toBe(firstEl)
    expect(after[0].value).toBe('typed-A')
  })

  it('行对象不被注入内部字段（会原样提交给后端）', () => {
    const rows = reactive<Row[]>([{ v: 'A' }])
    mountTable(rows)
    // key 挂在组件内部的 WeakMap 上，行对象本身保持干净
    expect(Object.keys(rows[0])).toEqual(['v'])
  })
})
