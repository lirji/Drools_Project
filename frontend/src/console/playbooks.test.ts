import { describe, it, expect } from 'vitest'
import {
  PLAYBOOKS, PLAYBOOK_GROUPS, filterPlaybooks, countByGroup, findPlaybook, isReady,
} from './playbooks'

/**
 * 玩法目录的**诚实性**回归。
 *
 * 这一屏最容易腐坏的方式不是渲染出错，而是有人为了让格子看起来满，
 * 给一个后端配不出来的玩法加上 preset——运营照着配、保存时被拒，或者更糟：保存成功但线上不生效。
 */

// field-dict 实际返回的可用条件字段与算子（对着 /activity-marketing/field-dict 核过）
const FIELDS = ['orderAmount', 'quantity', 'userDistrictId', 'userTags', 'spuId', 'storeId']
const OPS_BY_FIELD: Record<string, string[]> = {
  orderAmount: ['eq', 'gt', 'ge', 'lt', 'le', 'between'],
  quantity: ['eq', 'gt', 'ge', 'lt', 'le', 'between'],
  userDistrictId: ['eq', 'in', 'notIn'],
  userTags: ['contains', 'notContains', 'containsAny'],
  spuId: ['eq', 'in'],
  storeId: ['eq', 'in'],
}

describe('玩法目录', () => {
  it('id 唯一', () => {
    expect(new Set(PLAYBOOKS.map((p) => p.id)).size).toBe(PLAYBOOKS.length)
  })

  it('每个玩法都有一句人话，且不是占位', () => {
    for (const p of PLAYBOOKS) {
      expect(p.plain.length, p.id).toBeGreaterThan(8)
      expect(p.plain).not.toMatch(/敬请期待|TODO|待补/)
    }
  })

  // 三个曾经 blocked 的玩法（第二件半价 / 一口价秒杀 / 加价购）已随决策入口补订单行、
  // 新增一口价形态与两阶段决策全部解锁，所以现在 blocked 组**允许为空**。
  // 这条约束本身保留：将来若又出现做不到的玩法，仍必须写明缺什么、且不许给 preset，
  // 不许退回「敬请期待」。
  it('若有不可用的玩法，必须写明**缺什么**，且不许给 preset', () => {
    const blocked = PLAYBOOKS.filter((p) => p.group === 'blocked')
    for (const p of blocked) {
      expect(p.preset, `${p.id} 不可用却带了 preset —— 运营会配出一个保存不了的活动`).toBeUndefined()
      expect(p.blockedReason, p.id).toBeTruthy()
      expect(p.blockedReason!.length, p.id).toBeGreaterThan(20)
      expect(p.blockedReason).not.toMatch(/敬请期待|暂不开放$/)
      expect(isReady(p)).toBe(false)
    }
  })

  it('可用的玩法必须给 preset，且活动类型只能是后端放行的 1 / 5 / 6', () => {
    const ready = PLAYBOOKS.filter((p) => p.group !== 'blocked')
    expect(ready.length).toBeGreaterThan(0)
    for (const p of ready) {
      expect(p.preset, p.id).toBeDefined()
      expect([1, 5, 6], p.id).toContain(p.preset!.activityType)
      expect(isReady(p)).toBe(true)
    }
  })

  it('条件种子只能用 field-dict 真实存在的字段与该字段允许的算子', () => {
    for (const p of PLAYBOOKS) {
      for (const c of p.preset?.conditions ?? []) {
        expect(FIELDS, `${p.id} 用了不存在的字段 ${c.field}`).toContain(c.field)
        expect(OPS_BY_FIELD[c.field], `${p.id} 的 ${c.field} 不支持算子 ${c.op}`).toContain(c.op)
        expect(String(c.value).length, `${p.id} 条件值为空`).toBeGreaterThan(0)
      }
    }
  })

  it('权益形态只能是后端真正实现的五种，且各自的必填项要齐', () => {
    for (const p of PLAYBOOKS) {
      if (!p.preset) continue
      // 五种形态与后端 BenefitForm 一一对应：
      // fixed/ladder→AMOUNT、ratio→RATIO_ZHE、price→FIXED_PRICE、nth→NTH_ZHE
      expect(['fixed', 'ladder', 'ratio', 'price', 'nth'], p.id).toContain(p.preset.redMode)
      // 阶梯模式必须真给档位，否则模板等于没填
      if (p.preset.redMode === 'ladder') expect(p.preset.ladder?.length, p.id).toBeGreaterThan(0)
      if (p.preset.redMode === 'ratio') {
        // 折数越界或没有封顶的模板，运营点「用它新建」后会被写平面拒掉
        expect(p.preset.amount, p.id).toBeGreaterThan(0)
        expect(p.preset.amount, p.id).toBeLessThan(10)
        expect(p.preset.maxDiscount, `${p.id} 折扣模板必须带封顶——不封顶等于无上限支出`).toBeGreaterThan(0)
      }
      if (p.preset.redMode === 'price') {
        // 一口价是"卖多少"，必须为正——0 等于白送、负数等于倒贴
        expect(p.preset.amount, `${p.id} 一口价必须为正`).toBeGreaterThan(0)
      }
      if (p.preset.redMode === 'nth') {
        // 第 N 件折：折数同 ratio 的取值域，N 必须 ≥2（1 等于全场打折，那是 ratio）
        expect(p.preset.amount, p.id).toBeGreaterThan(0)
        expect(p.preset.amount, p.id).toBeLessThan(10)
        expect(p.preset.nth, `${p.id} 第 N 件折的 N 必须 ≥2`).toBeGreaterThanOrEqual(2)
      }
    }
  })

  it('阶梯档位首尾相接、最后一档无上限（与后端 [min,max) 语义一致）', () => {
    const ladder = findPlaybook('ladder')!.preset!.ladder!
    for (let i = 1; i < ladder.length; i++) {
      expect(ladder[i].min, '上一档 max 必须等于下一档 min，否则中间是断档').toBe(ladder[i - 1].max)
    }
    expect(ladder[ladder.length - 1].max, '最后一档必须无上限，否则超过它就一分钱不减').toBe('')
  })

  it('曾经不可用的三个玩法已解锁，且各自落到正确的形态上', () => {
    const byId = Object.fromEntries(PLAYBOOKS.map((p) => [p.id, p]))
    // 第二件半价：靠决策入口新增的订单行（逐行单价）才算得出来
    expect(byId['second-half'].group).not.toBe('blocked')
    expect(byId['second-half'].preset!.redMode).toBe('nth')
    expect(byId['second-half'].preset!.nth).toBeGreaterThanOrEqual(2)
    // 限时秒杀：一口价形态 + 写平面的库存原子抢占
    expect(byId['flash'].group).not.toBe('blocked')
    expect(byId['flash'].preset!.redMode).toBe('price')
    // 加价购：两阶段决策，活动类型 6
    expect(byId['addon'].group).not.toBe('blocked')
    expect(byId['addon'].preset!.activityType).toBe(6)
  })

  it('折扣类已可用（2026-08 引擎加了按比例形态），且模板自带封顶', () => {
    const d = findPlaybook('discount')!
    expect(d.group).not.toBe('blocked')
    expect(d.preset?.redMode).toBe('ratio')
    expect(d.preset?.maxDiscount).toBeGreaterThan(0)
  })

  it('分组计数与筛选自洽', () => {
    expect(countByGroup('all')).toBe(PLAYBOOKS.length)
    let sum = 0
    for (const g of PLAYBOOK_GROUPS) {
      if (g.key === 'all') continue
      sum += countByGroup(g.key)
      expect(filterPlaybooks(g.key).every((p) => p.group === g.key)).toBe(true)
    }
    expect(sum, '每个玩法必须恰好属于一个分组').toBe(PLAYBOOKS.length)
  })

  it('findPlaybook 对未知 id / 空值返回 null 而不是抛错', () => {
    expect(findPlaybook('不存在')).toBeNull()
    expect(findPlaybook(null)).toBeNull()
    expect(findPlaybook(undefined)).toBeNull()
  })
})
