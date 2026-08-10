import { describe, expect, it } from 'vitest'
import {
  benefitDraftFromRule,
  benefitRequestFields,
  type BenefitDraftFields,
  type BenefitRuleLike,
} from './logic'

const RULES: Array<{ name: string; rule: BenefitRuleLike }> = [
  { name: '固定金额', rule: { redPackageTakeType: 1, redPackageAmountUnit: '元', redPackageAmount: 10, redPackageMaxDiscount: null, redPackageRangeAmount: null } },
  { name: '随机金额', rule: { redPackageTakeType: 2, redPackageAmountUnit: '元', redPackageAmount: null, redPackageMaxDiscount: null, redPackageRangeAmount: '{"min":5,"max":20}' } },
  { name: '阶梯分档', rule: { redPackageTakeType: null, redPackageAmountUnit: '元', redPackageAmount: null, redPackageMaxDiscount: null, redPackageRangeAmount: '[{"min":0,"max":100,"reward":5}]' } },
  // 阶梯 + 底价：后端合法组合（金标「订单金额缺失 → 阶梯不参与，退回固定金额」期望减 7）。
  // 早先版本在 ladder 分支硬把 redPackageAmount 写成 null，一次零改动的编辑就把底价清掉了。
  { name: '阶梯带底价', rule: { redPackageTakeType: null, redPackageAmountUnit: '元', redPackageAmount: 7, redPackageMaxDiscount: null, redPackageRangeAmount: '[{"min":0,"max":100,"reward":5}]' } },
  { name: '折扣', rule: { redPackageTakeType: null, redPackageAmountUnit: '折', redPackageAmount: 8, redPackageMaxDiscount: 50, redPackageRangeAmount: null } },
  { name: '一口价', rule: { redPackageTakeType: null, redPackageAmountUnit: '价', redPackageAmount: 9.9, redPackageMaxDiscount: null, redPackageRangeAmount: null } },
  { name: '第 N 件折', rule: { redPackageTakeType: null, redPackageAmountUnit: '件折', redPackageAmount: 5, redPackageMaxDiscount: null, redPackageRangeAmount: '{"nth":3}' } },
]

const DRAFTS: Array<{ name: string; draft: BenefitDraftFields }> = [
  { name: '固定金额', draft: { redMode: 'fixed', amount: 10, maxDiscount: '', rangeMin: '', rangeMax: '', nth: '', ladder: [] } },
  { name: '随机金额', draft: { redMode: 'random', amount: '', maxDiscount: '', rangeMin: 5, rangeMax: 20, nth: '', ladder: [] } },
  { name: '阶梯分档', draft: { redMode: 'ladder', amount: '', maxDiscount: '', rangeMin: '', rangeMax: '', nth: '', ladder: [{ min: 0, max: 100, reward: 5 }] } },
  { name: '阶梯带底价', draft: { redMode: 'ladder', amount: 7, maxDiscount: '', rangeMin: '', rangeMax: '', nth: '', ladder: [{ min: 0, max: 100, reward: 5 }] } },
  { name: '折扣', draft: { redMode: 'ratio', amount: 8, maxDiscount: 50, rangeMin: '', rangeMax: '', nth: '', ladder: [] } },
  { name: '一口价', draft: { redMode: 'price', amount: 9.9, maxDiscount: '', rangeMin: '', rangeMax: '', nth: '', ladder: [] } },
  { name: '第 N 件折', draft: { redMode: 'nth', amount: 5, maxDiscount: '', rangeMin: '', rangeMax: '', nth: 3, ladder: [] } },
]

describe('权益 rule ↔ draft 正逆映射金标', () => {
  it.each(RULES)('$name: toRequest(fromRule(rule)) 保持五个判别字段', ({ rule }) => {
    const { parsed: _parsed, ...draft } = benefitDraftFromRule(rule)
    expect(benefitRequestFields(draft)).toEqual(rule)
  })

  it.each(DRAFTS)('$name: fromRule(toRequest(draft)) 保持形态拥有字段', ({ draft }) => {
    const { parsed, ...roundtrip } = benefitDraftFromRule(benefitRequestFields(draft))
    expect(parsed).toBe(true)
    expect(roundtrip).toEqual(draft)
  })

  it('零改动编辑「阶梯 + 底价」不得丢掉底价（会静默改钱）', () => {
    const stored: BenefitRuleLike = {
      redPackageTakeType: null,
      redPackageAmountUnit: '元',
      redPackageAmount: 7,
      redPackageMaxDiscount: null,
      redPackageRangeAmount: '[{"min":0,"max":100,"reward":5}]',
    }
    const { parsed: _p, ...draft } = benefitDraftFromRule(stored)
    // 运营打开编辑页什么都没改就保存 → 提交的底价必须还是 7，不能变成 null。
    // 丢了它，缺 orderAmount 的单会从「减 7」变成 0 元候选，落档的单则改按档位发。
    expect(benefitRequestFields(draft).redPackageAmount).toBe(7)
  })

  it('新建纯阶梯时底价仍为 null（不得凭空造出一个 0 元底价）', () => {
    const fresh: BenefitDraftFields = { redMode: 'ladder', amount: '', maxDiscount: '', rangeMin: '', rangeMax: '', nth: '', ladder: [{ min: 0, max: 100, reward: 5 }] }
    expect(benefitRequestFields(fresh).redPackageAmount).toBeNull()
  })

  it('非红包活动统一清空权益字段', () => {
    expect(benefitRequestFields(DRAFTS[0].draft, false)).toEqual({
      redPackageTakeType: null,
      redPackageAmount: null,
      redPackageAmountUnit: '元',
      redPackageMaxDiscount: null,
      redPackageRangeAmount: null,
    })
  })
})
