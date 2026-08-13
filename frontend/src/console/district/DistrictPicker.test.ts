import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import DistrictPicker from './DistrictPicker.vue'
import type { District } from '@/shared/types'
import { MAX_DISTRICTS } from './districtLogic'

const d = (code: string, name: string, level: 1 | 2 | 3, parent: string | null,
           shortName = name, pinyin = '', pinyinInitial = ''): District =>
  ({ code, name, shortName, level, parent, pinyin, pinyinInitial })

const FIXTURE: District[] = [
  d('440000', '广东省', 1, null, '广东', 'guangdong', 'g'),
  d('440300', '深圳市', 2, '440000', '深圳', 'shenzhen', 's'),
  d('440305', '南山区', 3, '440300', '南山', 'nanshan', 'n'),
  d('440304', '福田区', 3, '440300', '福田', 'futian', 'f'),
  d('110000', '北京市', 1, null, '北京', 'beijing', 'b'),
  d('110101', '东城区', 3, '110000', '东城', 'dongcheng', 'd'),
]

function mountPicker(props: Partial<{ districts: District[] | null; modelValue: string[]; failed: boolean; loading: boolean }> = {}) {
  return mount(DistrictPicker, { props: { districts: FIXTURE, modelValue: [], ...props } })
}
function lastEmit(w: ReturnType<typeof mountPicker>): string[] | undefined {
  const ev = w.emitted('update:modelValue')
  return ev ? (ev[ev.length - 1][0] as string[]) : undefined
}

describe('DistrictPicker · 计数与空态', () => {
  it('一个都没选：计数 0、给空态提示', () => {
    const w = mountPicker({ modelValue: [] })
    expect(w.get('[data-testid="district-count"]').text()).toContain('已选 0 / ' + MAX_DISTRICTS)
    expect(w.find('[data-testid="district-empty-hint"]').exists()).toBe(true)
    expect(w.find('[data-testid="district-chips"]').exists()).toBe(false)
  })
})

describe('DistrictPicker · 完整已选清单（按省分组）', () => {
  it('已选按省分组显示：广东下深圳、北京下东城', () => {
    const w = mountPicker({ modelValue: ['440300', '110101'] })
    const t = w.get('[data-testid="district-chips"]').text()
    expect(t).toContain('广东')
    expect(t).toContain('深圳')
    expect(t).toContain('北京')
    expect(t).toContain('东城')
  })

  it('整省选择的项显示「全省」', () => {
    const w = mountPicker({ modelValue: ['440000'] })
    expect(w.get('[data-testid="district-chips"]').text()).toContain('全省')
  })

  it('移除某一项只摘该码，保留其它', async () => {
    const w = mountPicker({ modelValue: ['440300', '110101'] })
    await w.get('[data-testid="district-chip-x-440300"]').trigger('click')
    expect(lastEmit(w)).toEqual(['110101'])
  })

  it('清空一键清掉全部', async () => {
    const w = mountPicker({ modelValue: ['440300', '110101'] })
    await w.get('[data-testid="district-clear"]').trigger('click')
    expect(lastEmit(w)).toEqual([])
  })
})

describe('DistrictPicker · 未知/已撤销码', () => {
  it('未知码单列在「未知代码」小节、并给顶部提示，且不阻断（原样保留）', () => {
    // 500105 江北区 2025-11 撤销，字典查不到
    const w = mountPicker({ modelValue: ['500105', '440305'] })
    expect(w.get('[data-testid="district-chips"]').text()).toContain('500105')
    expect(w.get('[data-testid="district-unknown"]').text()).toContain('可能已撤销')
  })

  it('移除未知码同样按码摘除', async () => {
    const w = mountPicker({ modelValue: ['500105', '440305'] })
    await w.get('[data-testid="district-chip-x-500105"]').trigger('click')
    expect(lastEmit(w)).toEqual(['440305'])
  })
})

describe('DistrictPicker · 上限提示', () => {
  it('选满 146 个给上限提示', () => {
    const full = Array.from({ length: MAX_DISTRICTS }, (_, i) => String(900000 + i))
    const w = mountPicker({ modelValue: full })
    expect(w.find('[data-testid="district-limit"]').exists()).toBe(true)
  })
})

describe('DistrictPicker · 字典降级逃生门', () => {
  it('字典失败：出降级条 + 裸 CSV 逃生门', () => {
    const w = mountPicker({ districts: null, failed: true })
    expect(w.find('[data-testid="district-warning"]').exists()).toBe(true)
    expect(w.find('[data-testid="district-raw"]').exists()).toBe(true)
  })

  it('逃生门能输入多个码，逗号不会被边打边吞（rawDraft 保留原字符串）', async () => {
    const w = mountPicker({ districts: null, failed: true, modelValue: [] })
    const raw = w.get('[data-testid="district-raw"]')
    await raw.setValue('440300,') // 打到逗号这一刻
    expect((raw.element as HTMLInputElement).value).toBe('440300,') // 逗号还在，没被规范化吞掉
    await raw.setValue('440300,110000')
    expect(lastEmit(w)).toEqual(['440300', '110000'])
  })
})
