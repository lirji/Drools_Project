import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Icon from './Icon.vue'

describe('Icon（内联 SVG 原语）', () => {
  it('按 name 渲染 svg，默认 aria-hidden', () => {
    const w = mount(Icon, { props: { name: 'list' } })
    const svg = w.find('svg')
    expect(svg.exists()).toBe(true)
    expect(svg.attributes('aria-hidden')).toBe('true')
    expect(svg.attributes('stroke')).toBe('currentColor')
    // list 图标含横线 path
    expect(svg.html()).toContain('<path')
  })

  it('size / stroke 透传到 svg', () => {
    const w = mount(Icon, { props: { name: 'plus', size: 20, stroke: 2 } })
    const svg = w.find('svg')
    expect(svg.attributes('width')).toBe('20')
    expect(svg.attributes('height')).toBe('20')
    expect(svg.attributes('stroke-width')).toBe('2')
  })

  it('透传 data-testid / aria-label（fallthrough attrs）', () => {
    const w = mount(Icon, { props: { name: 'log-out' }, attrs: { 'data-testid': 'x-icon', 'aria-label': '登出' } })
    const svg = w.find('svg')
    expect(svg.attributes('data-testid')).toBe('x-icon')
    expect(svg.attributes('aria-label')).toBe('登出')
  })

  it('未知 name 回退不抛错', () => {
    const w = mount(Icon, { props: { name: 'no-such-icon' } })
    expect(w.find('svg').exists()).toBe(true)
  })

  it('不同 name 产出不同 path 内容', () => {
    const a = mount(Icon, { props: { name: 'home' } }).find('svg').html()
    const b = mount(Icon, { props: { name: 'trash' } }).find('svg').html()
    expect(a).not.toBe(b)
  })
})
