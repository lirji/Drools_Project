import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import WindowBar from './WindowBar.vue'
import Gauge from './Gauge.vue'
import Receipt from './Receipt.vue'
import Sparkline from './Sparkline.vue'
import Seam from './Seam.vue'

const DAY = 86_400_000
const NOW = new Date('2026-11-06T00:00:00Z')
const d = (n: number) => new Date(+NOW + n * DAY)

/**
 * 数学对不代表画对——这一层验证几何**真的落到了 DOM 上**。
 * 单测纯函数能防「算错」，但防不住「算对了却没绑到 style 上」「条件写反导致截断箭头不出现」这类接线错误。
 */
describe('WindowBar', () => {
  it('游标位置写进 style，且跨实例完全相同', () => {
    const a = mount(WindowBar, { props: { start: d(-10), end: d(10), now: NOW } })
    const b = mount(WindowBar, { props: { start: d(-50), end: d(-40), now: NOW } })
    const posA = (a.find('.now').element as HTMLElement).style.left
    const posB = (b.find('.now').element as HTMLElement).style.left
    expect(posA).toBe(posB)
    expect(posA.startsWith('66.6')).toBe(true)
  })

  it('越界两端各自渲染截断箭头', () => {
    const past = mount(WindowBar, { props: { start: d(-200), end: d(-30), now: NOW } })
    expect(past.find('.cut.l').exists()).toBe(true)
    expect(past.find('.cut.r').exists()).toBe(false)

    const both = mount(WindowBar, { props: { start: d(-200), end: d(200), now: NOW } })
    expect(both.find('.cut.l').exists()).toBe(true)
    expect(both.find('.cut.r').exists()).toBe(true)
  })

  it('完全在轴域外 → 不画条（但仍画轴与游标，保持行高一致）', () => {
    const w = mount(WindowBar, { props: { start: d(-300), end: d(-200), now: NOW } })
    expect(w.find('.bar').exists()).toBe(false)
    expect(w.find('.track').exists()).toBe(true)
    expect(w.find('.now').exists()).toBe(true)
  })

  it('已下线不画条', () => {
    const w = mount(WindowBar, { props: { start: d(-10), end: d(10), now: NOW, muted: true } })
    expect(w.find('.bar').exists()).toBe(false)
  })

  it('aria-label 说人话（读屏用户拿到的是结论不是坐标）', () => {
    const w = mount(WindowBar, { props: { start: d(-10), end: d(10), now: NOW } })
    expect(w.find('.wb').attributes('aria-label')).toContain('生效窗')
  })
})

describe('Gauge', () => {
  it('越线时颜色类与文字同时改变（双编码）', () => {
    const ok = mount(Gauge, { props: { percent: 63.1 } })
    expect(ok.find('.tube').classes()).not.toContain('over')
    expect(ok.find('.lab').text()).toBe('63.1%')

    const over = mount(Gauge, { props: { percent: 91.4 } })
    expect(over.find('.tube').classes()).toContain('over')
    expect(over.find('.lab').text()).toBe('91.4% 越线')
    expect(over.find('.lab').classes()).toContain('over')
  })

  it('临界线位置可配，默认 80%', () => {
    expect((mount(Gauge, { props: { percent: 50 } }).find('.crit').element as HTMLElement).style.left).toBe('80%')
    expect((mount(Gauge, { props: { percent: 50, threshold: 60 } }).find('.crit').element as HTMLElement).style.left).toBe('60%')
  })

  it('未启用时不渲染量筒，只给文字', () => {
    const w = mount(Gauge, { props: { percent: null } })
    expect(w.find('.tube').exists()).toBe(false)
    expect(w.text()).toBe('未启用')
  })
})

describe('Receipt', () => {
  it('金额两位小数右对齐，命中行加标记', () => {
    const w = mount(Receipt, {
      props: {
        lines: [{ label: '满 300 减', amount: 50, hit: true }, { label: '满 600 减', amount: 120 }],
        total: { label: '今日已核销', amount: 184320 },
      },
    })
    const amts = w.findAll('.amt').map((a) => a.text())
    expect(amts[0]).toContain('50.00')
    expect(amts[1]).toContain('120.00')
    expect(amts[2]).toContain('184,320.00')     // 千分位
    expect(w.findAll('.row.hit')).toHaveLength(1)
    expect(w.find('.total').exists()).toBe(true)
  })

  it('没有 total 时不画会计双线', () => {
    const w = mount(Receipt, { props: { lines: [{ label: 'a', amount: 1 }] } })
    expect(w.find('.total').exists()).toBe(false)
  })
})

describe('Sparkline', () => {
  it('数据不足时画虚线基线而不是空图', () => {
    const w = mount(Sparkline, { props: { values: [] } })
    expect(w.find('path').classes()).toContain('empty')
    expect(w.find('path').attributes('d')).toBe('M0,15L100,15')
  })

  it('正常数据不带 empty 类，且线宽不随拉伸变形', () => {
    const w = mount(Sparkline, { props: { values: [1, 5, 3] } })
    expect(w.find('path').classes()).not.toContain('empty')
    expect(w.find('path').attributes('vector-effect')).toBe('non-scaling-stroke')
    expect(w.find('svg').attributes('preserveAspectRatio')).toBe('none')
  })
})

describe('Seam', () => {
  it('渲染成分隔语义，缺口底色由使用点的 --notch-bg 决定（不写死）', () => {
    const w = mount(Seam)
    expect(w.find('.seam').attributes('role')).toBe('separator')
    // 组件自身不设 --notch-bg —— 写死会在三种上下文里错两种（设计评审 X3）
    expect(w.html()).not.toContain('--notch-bg:')
  })
})
