import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import DistrictCascader from './DistrictCascader.vue'
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

function mountCasc(props: Partial<{ districts: District[] | null; selected: string[]; loading: boolean }> = {}) {
  return mount(DistrictCascader, {
    props: { districts: FIXTURE, selected: [], ...props },
  })
}

/** 最近一次 `update:selected` 的载荷。 */
function lastEmit(w: ReturnType<typeof mountCasc>): string[] | undefined {
  const ev = w.emitted('update:selected')
  return ev ? (ev[ev.length - 1][0] as string[]) : undefined
}

describe('DistrictCascader · 勾选语义', () => {
  /**
   * 这是整个组件最重要的一条：勾「广东省」emit 的是 **`['440000']` 一个码**，不是它底下 143 个后代。
   *
   * 展开是**后端保存时**做的（`ActivityMarketingService.mergeDistrictCondition` → `expandWithDescendants`）。
   * 前端要是自作主张展开，144 个码 × 7 字符 = 1008 字符，一个省就把 varchar(1024) 吃光，
   * 运营再也选不了第二个省——而且这个上限是在**保存时**才炸出来的。
   */
  it('勾选省级 emit 的是那一个码，绝不在前端展开成后代', async () => {
    const w = mountCasc()
    await w.get('[data-testid="district-opt-440000"]').setValue(true)
    expect(lastEmit(w)).toEqual(['440000'])
  })

  it('取消勾选按码摘除，不动其它已选', async () => {
    const w = mountCasc({ selected: ['440000', '110000'] })
    await w.get('[data-testid="district-opt-440000"]').setValue(false)
    expect(lastEmit(w)).toEqual(['110000'])
  })

  it('祖先已选 → 后代禁用且置灰（再选一次只是白占名额，后端展开时本来就包含它）', async () => {
    const w = mountCasc({ selected: ['440000'] })
    await w.get('[data-testid="district-into-440000"]').trigger('click')
    const shenzhen = w.get('[data-testid="district-opt-440300"]')
    expect(shenzhen.attributes('disabled')).toBeDefined()
    // 禁用之外还要有视觉区分，否则运营只会觉得"点不动"而不知道为什么
    expect(shenzhen.element.closest('label')!.className).toContain('dim')
  })

  it('选中后代再选祖先 → 后代被摘掉，只留祖先（否则「已选 N 个」这个数字没法解释）', async () => {
    const w = mountCasc({ selected: ['440305'] })
    await w.get('[data-testid="district-opt-440000"]').setValue(true)
    expect(lastEmit(w)).toEqual(['440000'])
  })

  it(`选满 ${MAX_DISTRICTS} 个后，未选中的项一律禁用；已选中的仍可取消（否则就锁死了）`, async () => {
    const full = Array.from({ length: MAX_DISTRICTS }, (_, i) => String(900000 + i))
    const w = mountCasc({ selected: [...full.slice(1), '110000'] })
    expect(w.get('[data-testid="district-opt-440000"]').attributes('disabled')).toBeDefined()
    // 已选中的北京必须还能取消，否则达到上限 = 永久卡死
    const beijing = w.get('[data-testid="district-opt-110000"]')
    expect(beijing.attributes('disabled')).toBeUndefined()
    await beijing.setValue(false)
    expect(lastEmit(w)).toHaveLength(MAX_DISTRICTS - 1)
  })
})

describe('DistrictCascader · 下钻与面包屑', () => {
  it('下钻开出下一栏；面包屑回退（<768 时它是唯一的回退路径）', async () => {
    const w = mountCasc()
    expect(w.findAll('.col')).toHaveLength(1)

    await w.get('[data-testid="district-into-440000"]').trigger('click')
    expect(w.findAll('.col')).toHaveLength(2)
    expect(w.find('[data-testid="district-opt-440300"]').exists()).toBe(true)

    await w.get('[data-testid="district-into-440300"]').trigger('click')
    expect(w.findAll('.col')).toHaveLength(3)

    await w.get('[data-testid="district-crumb-440000"]').trigger('click')
    expect(w.findAll('.col')).toHaveLength(2) // 退回到「广东省」这一级
    await w.get('[data-testid="district-crumb-root"]').trigger('click')
    expect(w.findAll('.col')).toHaveLength(1)
  })

  it('两层直辖市：北京下钻直接是区县，且区县没有下钻按钮（不开空栏）', async () => {
    const w = mountCasc()
    await w.get('[data-testid="district-into-110000"]').trigger('click')
    expect(w.findAll('.col')).toHaveLength(2)
    expect(w.find('[data-testid="district-opt-110101"]').exists()).toBe(true)
    // 区县级没有下级 → 不该给下钻入口
    expect(w.find('[data-testid="district-into-110101"]').exists()).toBe(false)
  })

  it('下钻按钮不勾选、也不提交表单（它是 @click.prevent 的 button）', async () => {
    const w = mountCasc()
    await w.get('[data-testid="district-into-440000"]').trigger('click')
    expect(w.emitted('update:selected')).toBeUndefined()
  })
})

describe('DistrictCascader · 搜索', () => {
  it('简称 / 全拼都能命中，命中项跨级平铺并可直接勾选', async () => {
    const w = mountCasc()
    await w.get('[data-testid="district-search"]').setValue('nanshan')
    expect(w.find('[data-testid="district-hit-440305"]').exists()).toBe(true)
    await w.get('[data-testid="district-hit-440305"]').setValue(true)
    expect(lastEmit(w)).toEqual(['440305'])
  })

  it('搜不到时给空态而不是一片空白', async () => {
    const w = mountCasc()
    await w.get('[data-testid="district-search"]').setValue('不存在的地名')
    expect(w.find('[data-testid="district-hit-440305"]').exists()).toBe(false)
    expect(w.text()).toContain('没有匹配的行政区')
  })

  it('命中超过 50 条时给截断提示——不提示的话运营会以为"就这些"', async () => {
    const many = Array.from({ length: 80 }, (_, i) => d(String(500000 + i), `第${i}区`, 3, null))
    const w = mountCasc({ districts: many })
    await w.get('[data-testid="district-search"]').setValue('区')
    expect(w.findAll('[data-testid^="district-hit-"]')).toHaveLength(50)
    expect(w.get('[data-testid="district-search-trunc"]').text()).toContain('只显示前 50 条')
  })

  it('清空搜索按钮回到逐级列表', async () => {
    const w = mountCasc()
    await w.get('[data-testid="district-search"]').setValue('南山')
    await w.get('[data-testid="district-search-clear"]').trigger('click')
    expect(w.find('[data-testid="district-hit-440305"]').exists()).toBe(false)
    expect(w.find('[data-testid="district-opt-440000"]').exists()).toBe(true)
  })
})

describe('DistrictCascader · 降级形态', () => {
  it('字典为 null（还没拉到）不崩，也不渲染任何选项', () => {
    const w = mountCasc({ districts: null })
    expect(w.find('[data-testid="district-cascader"]').exists()).toBe(true)
    expect(w.findAll('[data-testid^="district-opt-"]')).toHaveLength(0)
  })

  it('字典为空数组（接口 200 但表空）同样不崩', () => {
    const w = mountCasc({ districts: [] })
    expect(w.findAll('[data-testid^="district-opt-"]')).toHaveLength(0)
  })

  it('loading 时出骨架屏，不出列表', () => {
    const w = mountCasc({ districts: null, loading: true })
    expect(w.findAll('[data-testid^="district-opt-"]')).toHaveLength(0)
    expect(w.find('[data-testid="district-search"]').exists()).toBe(true) // 搜索框恒在，避免布局跳动
  })
})

/**
 * 真实随包数据只跑**一条**冒烟：其余用 ≤10 行夹具。
 * 3212 行在 jsdom 里挂载一次就要渲染 34 个省级 li，再多跑几条纯属白烧 CI 时间——
 * 而数据本身的完整性（每行祖先可解析、已撤销码已剔除）由后端 `DistrictSeederTest` 守着。
 *
 * 这条守的是**前端这一侧**的假设：`buildIndex` 用 `parent == null` 判省级，
 * 如果哪天数据源把省级行的 `parent_code` 填成了自己或 `'0'`，后端那些用例照样绿，
 * 而这里会立刻从 34 掉到 0——表现在界面上是「第一栏整个空掉」。
 */
describe('DistrictCascader · 真实随包字典冒烟', () => {
  // jsdom 环境下 `import.meta.url` 不是 file: 协议，`new URL(...)` 那套在这里用不了。
  // 两个候选覆盖两种起跑点：`cd frontend && npx vitest`（常规）与仓库根（CI 可能这么跑）。
  const REL = 'activity-console/src/main/resources/district/china-district.csv'
  const CSV = [resolve(process.cwd(), '..', REL), resolve(process.cwd(), REL)].find(existsSync)!

  /** 与后端 `DistrictView.from` 同映射：拼音去空格、空上级归 null。 */
  function loadReal(): District[] {
    const lines = readFileSync(CSV, 'utf8').trim().split(/\r?\n/)
    return lines.slice(1).map((line) => {
      const f = line.split(',')
      expect(f).toHaveLength(11) // 名称里混进逗号会让整份字典错位，就地拦住
      // 列序：code,name,short_name,district_level,parent_code,province_code,city_code,full_name,pinyin,pinyin_initial,sort_no
      return d(f[0], f[1], Number(f[3]) as 1 | 2 | 3, f[4] || null, f[2],
        (f[8] || '').replace(/ /g, ''), f[9] || '')
    })
  }

  it('3212 行挂载不崩，省级栏恰 34 项，且能一路下钻到区县', async () => {
    const real = loadReal()
    expect(real).toHaveLength(3212)

    const w = mount(DistrictCascader, { props: { districts: real, selected: [] } })
    // 第一栏 = 全部省级：34 = 23 省 + 5 自治区 + 4 直辖市 + 2 特别行政区
    expect(w.findAll('.col')[0].findAll('li')).toHaveLength(34)

    await w.get('[data-testid="district-into-440000"]').trigger('click')
    await w.get('[data-testid="district-into-440300"]').trigger('click')
    expect(w.find('[data-testid="district-opt-440305"]').exists()).toBe(true) // 广东/深圳/南山
  })
})
