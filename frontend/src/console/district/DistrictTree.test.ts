import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import DistrictTree from './DistrictTree.vue'
import type { District } from '@/shared/types'
import { MAX_DISTRICTS } from './districtLogic'

const d = (code: string, name: string, level: 1 | 2 | 3, parent: string | null,
           shortName = name, pinyin = '', pinyinInitial = ''): District =>
  ({ code, name, shortName, level, parent, pinyin, pinyinInitial })

// 夹具两种形态都有：广东（省→市→区，三层）与北京（省→区，**两层**，117 个真实行政区是这种）。
const FIXTURE: District[] = [
  d('440000', '广东省', 1, null, '广东', 'guangdong', 'g'),
  d('440300', '深圳市', 2, '440000', '深圳', 'shenzhen', 's'),
  d('440305', '南山区', 3, '440300', '南山', 'nanshan', 'n'),
  d('440304', '福田区', 3, '440300', '福田', 'futian', 'f'),
  d('110000', '北京市', 1, null, '北京', 'beijing', 'b'),
  d('110101', '东城区', 3, '110000', '东城', 'dongcheng', 'd'),
]

function mountTree(props: Partial<{ districts: District[] | null; selected: string[]; loading: boolean }> = {}) {
  return mount(DistrictTree, { props: { districts: FIXTURE, selected: [], ...props } })
}
function lastEmit(w: ReturnType<typeof mountTree>): string[] | undefined {
  const ev = w.emitted('update:selected')
  return ev ? (ev[ev.length - 1][0] as string[]) : undefined
}
const indeterminate = (w: ReturnType<typeof mountTree>, code: string): boolean =>
  (w.get(`[data-testid="district-opt-${code}"]`).element as HTMLInputElement).indeterminate

describe('DistrictTree · 勾选语义', () => {
  /**
   * 整个组件最重要的一条：勾「广东省」emit 的是 **`['440000']` 一个码**，不是它底下 143 个后代。
   * 展开是后端保存时做的；前端自作主张展开会把一个省吃光 varchar(1024)。
   */
  it('勾选省级 emit 的是那一个码，绝不在前端展开成后代', async () => {
    const w = mountTree()
    await w.get('[data-testid="district-opt-440000"]').setValue(true)
    expect(lastEmit(w)).toEqual(['440000'])
  })

  it('取消勾选按码摘除，不动其它已选', async () => {
    const w = mountTree({ selected: ['440000', '110000'] })
    await w.get('[data-testid="district-opt-440000"]').setValue(false)
    expect(lastEmit(w)).toEqual(['110000'])
  })

  it('选中后代再选祖先 → 后代被摘掉，只留祖先', async () => {
    const w = mountTree({ selected: ['440305'] })
    await w.get('[data-testid="district-opt-440000"]').setValue(true)
    expect(lastEmit(w)).toEqual(['440000'])
  })

  it('祖先已选 → 展开后后代勾中、禁用且置灰（再选只是白占名额）', async () => {
    const w = mountTree({ selected: ['440000'] })
    await w.get('[data-testid="district-expand-440000"]').trigger('click')
    const shenzhen = w.get('[data-testid="district-opt-440300"]')
    expect((shenzhen.element as HTMLInputElement).checked).toBe(true)
    expect(shenzhen.attributes('disabled')).toBeDefined()
    // 禁用之外还要有视觉区分，否则运营只觉得「点不动」而不知为什么
    expect(shenzhen.element.closest('label')!.className).toContain('dim')
  })

  it(`选满 ${MAX_DISTRICTS} 个后，未选中项禁用；已选中的仍可取消（否则锁死）`, async () => {
    const full = Array.from({ length: MAX_DISTRICTS }, (_, i) => String(900000 + i))
    const w = mountTree({ selected: [...full.slice(1), '110000'] })
    expect(w.get('[data-testid="district-opt-440000"]').attributes('disabled')).toBeDefined()
    const beijing = w.get('[data-testid="district-opt-110000"]')
    expect(beijing.attributes('disabled')).toBeUndefined()
    await beijing.setValue(false)
    expect(lastEmit(w)).toHaveLength(MAX_DISTRICTS - 1)
  })
})

describe('DistrictTree · 展开与三态', () => {
  it('默认折叠只渲染省级；点展开三角原位插子级，子级用 v-if 惰性挂载', async () => {
    const w = mountTree()
    // 折叠态：只有 2 个省级根（广东/北京），子级不在 DOM
    expect(w.findAll('[data-testid^="district-opt-"]')).toHaveLength(2)
    expect(w.find('[data-testid="district-opt-440300"]').exists()).toBe(false)

    await w.get('[data-testid="district-expand-440000"]').trigger('click')
    expect(w.find('[data-testid="district-opt-440300"]').exists()).toBe(true)

    await w.get('[data-testid="district-expand-440000"]').trigger('click') // 再点收起
    expect(w.find('[data-testid="district-opt-440300"]').exists()).toBe(false)
  })

  it('点展开三角只切展开、不勾选、不提交（它是独立 button）', async () => {
    const w = mountTree()
    await w.get('[data-testid="district-expand-440000"]').trigger('click')
    expect(w.emitted('update:selected')).toBeUndefined()
  })

  it('叶子（区县）没有展开三角；两层直辖市的区同理', async () => {
    const w = mountTree()
    await w.get('[data-testid="district-expand-110000"]').trigger('click') // 北京 → 直接是区
    expect(w.find('[data-testid="district-opt-110101"]').exists()).toBe(true)
    expect(w.find('[data-testid="district-expand-110101"]').exists()).toBe(false) // 东城区无三角
  })

  it('半选态用原生 indeterminate 表达（部分后代被选、父级未选）', () => {
    // selected 含 440305 → 回读自动展开到已选路径（广东/深圳已展开、南山可见），无需手动展开
    const w = mountTree({ selected: ['440305'] })
    expect(indeterminate(w, '440000')).toBe(true) // 广东半选
    expect(indeterminate(w, '440300')).toBe(true) // 深圳半选
    expect((w.get('[data-testid="district-opt-440305"]').element as HTMLInputElement).checked).toBe(true)
    expect(indeterminate(w, '440305')).toBe(false) // 南山是全选、非半选
  })

  it('折叠全部一键回到只剩省级', async () => {
    const w = mountTree()
    await w.get('[data-testid="district-expand-440000"]').trigger('click')
    expect(w.find('[data-testid="district-opt-440300"]').exists()).toBe(true)
    await w.get('[data-testid="district-collapse-all"]').trigger('click')
    expect(w.find('[data-testid="district-opt-440300"]').exists()).toBe(false)
  })
})

describe('DistrictTree · 搜索（树内就地过滤）', () => {
  it('简称/全拼命中 → 只留命中+祖先并自动展开，命中仍是 district-opt 节点、可直接勾选', async () => {
    const w = mountTree()
    await w.get('[data-testid="district-search"]').setValue('nanshan')
    // 命中南山，其祖先广东/深圳一并可见；无关的北京被过滤掉
    expect(w.find('[data-testid="district-opt-440305"]').exists()).toBe(true)
    expect(w.find('[data-testid="district-opt-440000"]').exists()).toBe(true)
    expect(w.find('[data-testid="district-opt-110000"]').exists()).toBe(false)
    await w.get('[data-testid="district-opt-440305"]').setValue(true)
    expect(lastEmit(w)).toEqual(['440305'])
  })

  it('搜不到时给空态而不是一片空白', async () => {
    const w = mountTree()
    await w.get('[data-testid="district-search"]').setValue('不存在的地名')
    expect(w.find('[data-testid="district-opt-440305"]').exists()).toBe(false)
    expect(w.text()).toContain('没有匹配的行政区')
  })

  it('命中过多给截断提示', async () => {
    const many = Array.from({ length: 120 }, (_, i) => d(String(500000 + i), `第${i}区`, 1, null))
    const w = mountTree({ districts: many })
    await w.get('[data-testid="district-search"]').setValue('区')
    expect(w.find('[data-testid="district-search-trunc"]').exists()).toBe(true)
  })

  it('清空搜索回到逐级树', async () => {
    const w = mountTree()
    await w.get('[data-testid="district-search"]').setValue('南山')
    await w.get('[data-testid="district-search-clear"]').trigger('click')
    expect(w.find('[data-testid="district-opt-110000"]').exists()).toBe(true) // 北京重新可见
  })
})

describe('DistrictTree · 只看已选', () => {
  it('开启后只留有选中的分支并展开，无关分支隐藏', async () => {
    const w = mountTree({ selected: ['440305'] })
    await w.get('[data-testid="district-selected-only"]').trigger('click')
    expect(w.find('[data-testid="district-opt-440305"]').exists()).toBe(true)
    expect(w.find('[data-testid="district-opt-440000"]').exists()).toBe(true) // 祖先带出
    expect(w.find('[data-testid="district-opt-110000"]').exists()).toBe(false) // 未选分支隐藏
  })

  it('一个都没选时给空态', async () => {
    const w = mountTree({ selected: [] })
    await w.get('[data-testid="district-selected-only"]').trigger('click')
    expect(w.text()).toContain('没有已选的行政区')
  })
})

describe('DistrictTree · 降级形态', () => {
  it('字典为 null（还没拉到）不崩，也不渲染任何选项', () => {
    const w = mountTree({ districts: null })
    expect(w.find('[data-testid="district-tree"]').exists()).toBe(true)
    expect(w.findAll('[data-testid^="district-opt-"]')).toHaveLength(0)
  })

  it('loading 时出骨架屏，搜索框恒在避免布局跳动', () => {
    const w = mountTree({ districts: null, loading: true })
    expect(w.findAll('[data-testid^="district-opt-"]')).toHaveLength(0)
    expect(w.find('[data-testid="district-search"]').exists()).toBe(true)
  })
})

/**
 * 真实随包数据只跑**一条**冒烟。守的是前端这一侧的假设：`buildIndex` 用 `parent == null` 判省级 → 恰 34 省根，
 * 且折叠态下靠 `v-if` 惰性挂载：顶层 `district-opt-*` 恰 34 个（若改用 CSS 折叠，这个数会静默变大、e2e 也会跟着废）。
 */
describe('DistrictTree · 真实随包字典冒烟', () => {
  const REL = 'activity-console/src/main/resources/district/china-district.csv'
  const CSV = [resolve(process.cwd(), '..', REL), resolve(process.cwd(), REL)].find(existsSync)!

  function loadReal(): District[] {
    const lines = readFileSync(CSV, 'utf8').trim().split(/\r?\n/)
    return lines.slice(1).map((line) => {
      const f = line.split(',')
      expect(f).toHaveLength(11)
      return d(f[0], f[1], Number(f[3]) as 1 | 2 | 3, f[4] || null, f[2],
        (f[8] || '').replace(/ /g, ''), f[9] || '')
    })
  }

  it('3212 行折叠态恰 34 个省级 district-opt；可一路展开到区县', async () => {
    const real = loadReal()
    expect(real).toHaveLength(3212)

    const w = mount(DistrictTree, { props: { districts: real, selected: [] } })
    expect(w.findAll('[data-testid^="district-opt-"]')).toHaveLength(34)

    await w.get('[data-testid="district-expand-440000"]').trigger('click')
    await w.get('[data-testid="district-expand-440300"]').trigger('click')
    expect(w.find('[data-testid="district-opt-440305"]').exists()).toBe(true) // 广东/深圳/南山
  })
})
