package com.lrj.drools.activity.engine;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ActivityRuleResult;
import com.lrj.drools.activity.domain.BenefitForm;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderActivityDef;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderTier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 阶梯落档与折扣合并的**纯 Java 实现**（计划 P1-2 · 分层引擎第一步）。
 *
 * <p><b>为什么这两件事不该用规则引擎</b>——判据是「这条规则需不需要<em>其它规则的结论</em>」：
 * <ul>
 *   <li><b>阶梯落档</b>是纯标量分段函数。原实现给<b>每个档位生成一条 DRL 规则</b>，
 *       200 档就是 200 条规则、约 7.6MB KieBase，而换来的表达力是零。
 *       更糟的是 KieBase 体积随运营配置线性膨胀，直接喂大了缓存键爆炸（评估报告 D2）。</li>
 *   <li><b>折扣合并</b>是一次 reduce。原实现用 {@code $c : Candidate() and not Candidate(amount > $c.amount)}
 *       做 argmax——语义正确，但那是 O(N²) 的 beta 节点评估，只为求一个 max。
 *       候选 200 个就是 4 万次比较。</li>
 * </ul>
 * 规则引擎的价值在<em>规则之间的关系</em>，不在规则本身。真正该留给 Drools 的是互斥矩阵、
 * 级联改写、CEP 频控——那些才需要「其它规则的结论」。
 *
 * <p><b>本类是逐条复制 DRL 语义，不是重新设计</b>。下面每个方法都标注了它对应的原规则，
 * 包括几处容易被"顺手改好"的怪异行为（见 {@link #computeAmounts}）。等价性由
 * {@code BenefitEvaluatorParityTest} 对拍 + 39 例金标共同守住——**判据是两条路给出同样的钱**，
 * 不是「新实现看起来更合理」。
 */
@Service
public class BenefitEvaluator {

    /**
     * 阶梯落档。对应 DRL {@code ladder_i}：
     * <pre>
     * when  $ctx : ActivityRuleContext( numberAttr(field) != null &amp;&amp; &gt;= min &amp;&amp; &lt; max )
     *       $c   : ActivityCandidate( activityId == "…", eligible == true )
     * then  $c.setComputedAmount(reward);
     * </pre>
     *
     * <p>区间是 <b>[min, max)</b>——下界闭、上界开。金标集里 {@code orderAmount=100} 必须落到
     * {@code [100,200)} 那一档而不是 {@code [0,100)}，就是在钉这个边界。
     *
     * <p>注意它<b>只设 computedAmount、不设 amountComputed</b>——这不是疏漏，是原 DRL 的行为，
     * 后续 {@link #computeAmounts} 依赖这个细节，见那里的说明。
     */
    public void applyLadder(ActivityRuleContext ctx, List<ActivityCandidate> candidates,
                            List<LadderActivityDef> defs) {
        if (defs == null || defs.isEmpty()) return;
        for (LadderActivityDef def : defs) {
            BigDecimal driver = ctx.numberAttr(def.ladderField());
            if (driver == null) continue;                       // 缺字段 → 闸门不开（与 DRL 的 != null 一致）
            LadderTier tier = tierOf(def.tiers(), driver);
            if (tier == null) continue;
            for (ActivityCandidate c : candidates) {
                if (!c.isEligible()) continue;
                if (!def.activityId().equals(c.getActivityId())) continue;
                c.setComputedAmount(tier.reward());
            }
        }
    }

    /** 线性扫描找命中的档位。档位数量级是个位数到几十，二分的常数收益不如可读性重要。 */
    private static LadderTier tierOf(List<LadderTier> tiers, BigDecimal v) {
        for (LadderTier t : tiers) {
            boolean lowerOk = t.min() == null || v.compareTo(t.min()) >= 0;
            boolean upperOk = t.max() == null || v.compareTo(t.max()) < 0;
            if (lowerOk && upperOk) return t;
        }
        return null;
    }

    /**
     * 算额。对应 DRL {@code discount-compute-amount}（salience 100）：
     * <pre>
     * when  $c : ActivityCandidate( eligible == true, amountComputed == false, redPackageAmount != null )
     * then  modify($c) { setComputedAmount($c.getRedPackageAmount()), setAmountComputed(true) }
     * </pre>
     *
     * <p><b>一个必须原样保留的怪异行为</b>：阶梯只设了 {@code computedAmount}、没设
     * {@code amountComputed}。于是活动<b>同时配了阶梯和固定金额</b>时，这条规则会把阶梯算出来的
     * 结果<b>覆盖</b>成固定金额。看起来像 bug，但它是<b>当前线上语义</b>——
     * 金标用例「订单金额缺失 → 阶梯不参与，退回固定金额」正是靠它成立的。
     * 在一个「只搬不改」的批次里顺手"修好"它，等于在没有需求依据的情况下改钱。
     * 要改必须单独立项、单独对拍。
     */
    public void computeAmounts(ActivityRuleContext ctx, List<ActivityCandidate> candidates) {
        for (ActivityCandidate c : candidates) {
            if (!c.isEligible()) continue;
            if (c.isAmountComputed()) continue;
            if (c.getRedPackageAmount() == null) continue;

            // 形态判别必须在最前面。漏了它，「打 8 折」会被当成「减 8 元」原样发出去——
            // 而且看起来毫无异常：金额是正数、决策成功、日志干净。
            if (BenefitForm.of(c.getRedPackageAmountUnit()) == BenefitForm.RATIO_ZHE) {
                BigDecimal off = BenefitMath.ratioDiscount(
                        ctx == null ? null : ctx.getOrderAmount(),
                        c.getRedPackageAmount(),
                        c.getRedPackageMaxDiscount());
                // 算不出来（没订单金额 / 折数越界）→ **不给优惠**，而不是给 0 元：
                // 给 0 元会让它以 0 参与 MAX 竞争并可能挤掉别的活动。
                if (off == null) continue;
                c.setComputedAmount(off);
                c.setAmountComputed(true);
                continue;
            }

            c.setComputedAmount(c.getRedPackageAmount());
            c.setAmountComputed(true);
        }
    }

    /**
     * 合并。一次 O(N) 遍历取代原来的 O(N²) 规则自连接。
     *
     * <ul>
     *   <li><b>MAX</b>：{@code not exists(eligible, computedAmount > $c.computedAmount)} → 取金额最大者</li>
     *   <li><b>MUTEX / PRIORITY</b>：{@code not exists(priority < $c.priority || (== && amount > $c.amount))}
     *       → priority 数字小者胜，同 priority 再比金额大</li>
     *   <li><b>STACK</b>：全部 eligible 候选金额累加（{@code accumulate sum}），
     *       主活动 id 用与 PRIORITY 相同的选择规则（原 DRL 的 {@code discount-stack-main}）</li>
     * </ul>
     *
     * <p><b>平局是不确定的</b>：原 DRL 里多个候选同时满足 {@code not exists(...)} 时会各 fire 一次、
     * 后 fire 的覆盖先 fire 的，而 Drools 的 agenda 顺序不保证。也就是说
     * <b>「金额并列时命中哪个活动」在改造前就是未定义行为</b>。本实现取第一个（稳定、可复现），
     * 属于把未定义收敛成确定——不是行为变更。金额本身在两种实现下完全一致。
     */
    public ActivityRuleResult merge(List<ActivityCandidate> candidates, StackStrategy strategy) {
        return merge(candidates, strategy, false);
    }

    /** {@code explain=true} 时产出与原 DRL 同文案的 trace，控制台试算的展示不变。 */
    public ActivityRuleResult merge(List<ActivityCandidate> candidates, StackStrategy strategy, boolean explain) {
        ActivityRuleResult result = new ActivityRuleResult();
        result.setStrategy(strategy);

        List<ActivityCandidate> eligible = candidates.stream().filter(ActivityCandidate::isEligible).toList();
        if (eligible.isEmpty()) return result;

        if (strategy == StackStrategy.STACK) {
            BigDecimal total = BigDecimal.ZERO;
            for (ActivityCandidate c : eligible) {
                if (c.getComputedAmount() != null) total = total.add(c.getComputedAmount());
            }
            result.setHitAmount(total);
            if (explain) result.trace("stack sum amount=" + total);
            ActivityCandidate main = pickByPriority(eligible);
            if (main != null) {
                result.setHitActivityId(main.getActivityId());
                result.setHitActivityName(main.getActivityName());
                if (explain) result.trace("stack main activityId=" + main.getActivityId());
            }
            return result;
        }

        ActivityCandidate winner = (strategy == StackStrategy.MAX)
                ? pickByAmount(eligible)
                : pickByPriority(eligible);
        if (winner != null) {
            result.hit(winner);
            if (explain) {
                result.trace(strategy == StackStrategy.MAX
                        ? "hit by MAX: " + winner.getActivityId() + " amount=" + amount(winner)
                        : "hit by " + strategy.name() + ": " + winner.getActivityId()
                          + " priority=" + winner.getPriority() + " amount=" + amount(winner));
            }
        }
        return result;
    }

    /** MAX：金额最大者（并列取第一个，见类注释关于平局的说明）。 */
    private static ActivityCandidate pickByAmount(List<ActivityCandidate> eligible) {
        ActivityCandidate best = null;
        for (ActivityCandidate c : eligible) {
            if (best == null || amount(c).compareTo(amount(best)) > 0) best = c;
        }
        return best;
    }

    /** MUTEX / PRIORITY / STACK 主活动：priority 小者胜，同 priority 取金额大者。 */
    private static ActivityCandidate pickByPriority(List<ActivityCandidate> eligible) {
        ActivityCandidate best = null;
        for (ActivityCandidate c : eligible) {
            if (best == null) { best = c; continue; }
            if (c.getPriority() < best.getPriority()) { best = c; continue; }
            if (c.getPriority() == best.getPriority()
                    && amount(c).compareTo(amount(best)) > 0) best = c;
        }
        return best;
    }

    private static BigDecimal amount(ActivityCandidate c) {
        return c.getComputedAmount() == null ? BigDecimal.ZERO : c.getComputedAmount();
    }
}
