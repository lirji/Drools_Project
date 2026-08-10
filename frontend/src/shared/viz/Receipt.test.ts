import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import Receipt from './Receipt.vue'

describe('Receipt 单位', () => {
  it('金额默认按人民币两位小数渲染', () => {
    const wrapper = mount(Receipt, { props: { lines: [{ label: '优惠', amount: 5 }] } })
    expect(wrapper.text()).toContain('¥5.00')
  })

  it('折/件使用后缀单位，不显示人民币符号', () => {
    const wrapper = mount(Receipt, {
      props: { lines: [{ label: '第二件', amount: 5, unit: '折' }, { label: '赠品', amount: 1, unit: '件' }] },
    })
    expect(wrapper.text()).toMatch(/5\s*折/)
    expect(wrapper.text()).toMatch(/1\s*件/)
    expect(wrapper.text()).not.toContain('¥')
  })
})
