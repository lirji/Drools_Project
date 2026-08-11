package com.lrj.drools.activity.engine;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.ActivityRuleContext;
import com.lrj.drools.activity.domain.ActivityRuleResult;
import com.lrj.drools.activity.domain.BenefitForm;
import com.lrj.drools.activity.domain.DistributionMode;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
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
 * {@code DecisionGoldenSetTest}（52 例金标）守住——**判据是给出同样的钱**，不是「新实现看起来更合理」。
 *
 * <p>（历史注记：这里原先还写着「{@code BenefitEvaluatorParityTest} 对拍」。那个对拍测试随旧 DRL 算额路径
 * 一并删除后就不存在了，全仓库查无此文件；对拍的另一方 {@code buildDiscountDrl} 也已删。别去找它，
 * 也别为了让这句注释成立而重新造一条 DRL 算额路径——那等于复活刚被删掉的第二权威。）
 */
@Service
public class BenefitEvaluator {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BenefitEvaluator.class);

    private final com.lrj.drools.activity.metrics.DecisionMetrics metrics;

    public BenefitEvaluator(com.lrj.drools.activity.metrics.DecisionMetrics metrics) {
        this.metrics = metrics;
    }

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
     *
     * <p>落档时另置 {@code ladderApplied}。它<b>不参与</b>上面那套覆盖语义，只回答一个
     * computedAmount 回答不了的问题：这个 0 元是「本档就是 0 元」还是「压根没落档」。
     * 三条闸门不开的路径（缺字段 / 未落档 / 负奖励）都不打这个标记，
     * 于是没有其它金额来源的候选会在 {@link #computeAmounts} 里被淘汰而不是留成 0 元幽灵。
     */
    public void applyLadder(ActivityRuleContext ctx, List<ActivityCandidate> candidates,
                            List<LadderActivityDef> defs) {
        if (defs == null || defs.isEmpty()) return;
        for (LadderActivityDef def : defs) {
            BigDecimal driver = ctx.numberAttr(def.ladderField());
            if (driver == null) continue;                       // 缺字段 → 闸门不开（与 DRL 的 != null 一致）
            LadderTier tier = tierOf(def.tiers(), driver);
            if (tier == null) continue;
            // 负奖励 = 负优惠（下游会去加钱）。写入口现在拦得住新配置，但拦不住已经在库里的脏数据，
            // 而决策出口的闸门是 `hitActivityId != null || hitAmount > 0`——OR 短路让负数照样出门。
            // 落档前挡一次，语义与其它 fail-closed 分支一致：算出来不对，就是本活动不适用。
            if (tier.reward() != null && tier.reward().signum() < 0) continue;
            for (ActivityCandidate c : candidates) {
                if (!c.isEligible()) continue;
                if (!def.activityId().equals(c.getActivityId())) continue;
                c.setComputedAmount(tier.reward());
                // 落过档要留痕：computeAmounts 靠它区分「本档就是 0 元」与「压根没落档」。
                c.setLadderApplied(true);
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

            // BenefitForm 是「redPackageAmount 这个数是什么意思」的最高优先级判别位。
            // takeType 只在金额型里区分固定/随机；否则 API 手造的「折 + takeType=2」会在这里
            // 被错误抢进随机分支，永远走不到下面的折扣计算。
            BenefitForm form = BenefitForm.of(c.getRedPackageAmountUnit());

            // 随机红包必须排在 `redPackageAmount == null` 那道 guard **之前**：
            // 它的金额来自区间（redPackageRangeAmount），而不是 redPackageAmount 那个字段，
            // 放在 guard 之后的话「只配了区间、没配固定金额」的随机活动会被静默跳过。
            if (form == BenefitForm.AMOUNT && DistributionMode.RANDOM_AMOUNT == distributionOf(c)) {
                BigDecimal drawn = drawRandom(ctx, c);
                // 算不出来（区间缺失/非法）→ 不给优惠，而不是给 0 元。同 ratioDiscount 的规矩：
                // 0 元会以 0 参与 MAX 竞争并可能挤掉别的活动。
                if (drawn == null) { notApplicable(c, "bad-random-range", "随机区间缺失或非法"); continue; }
                c.setComputedAmount(drawn);
                c.setAmountComputed(true);
                continue;
            }

            // 走到这里说明既没有固定金额，也不是随机型（随机在上面已算完）——
            // 这个候选唯一可能的金额来源就是阶梯。阶梯没落过档 = 算不出金额 = 本活动不适用。
            //
            // 早先这里是一句裸 continue，于是候选带着 eligible=true / computedAmount=0 进入合并：
            // PRIORITY / MUTEX 下它凭 priority 就能挤掉真正能减钱的活动（用户一分钱拿不到），
            // 单候选 MAX 下则变成「命中 X，减 0 元」的假命中。规则行缺失的候选也走这一支。
            //
            // 判据必须是「算不算得出来」而不是「金额是不是 0」：首档 reward=0 是运营配得出来的
            // 合法 0 元优惠，用金额判别会误杀它（NotApplicableCandidateTest#legitimateZeroSurvives）。
            // 所以靠 ladderApplied 这个落档留痕来区分，而不是看 computedAmount。
            if (c.getRedPackageAmount() == null) {
                if (!c.isLadderApplied()) { notApplicable(c, "no-ladder-tier", "阶梯未落档且无固定金额"); }
                continue;
            }

            // 第 N 件折（第二件半价）：折数在 redPackageAmount、第几件在 redPackageRangeAmount 的 {"nth":N}。
            // 它必须有逐行单价才算得出来——缺 lines 时返回 null（不适用），
            // **绝不退化成拿整单均价算**：混着贵重与便宜商品的车会静默算错钱。
            if (form == BenefitForm.NTH_ZHE) {
                BigDecimal off = nthDiscount(ctx, c);
                if (off == null) { notApplicable(c, "missing-lines", "第 N 件折缺订单行或 N 非法"); continue; }
                c.setComputedAmount(off);
                c.setAmountComputed(true);
                continue;
            }

            // 一口价（秒杀）：redPackageAmount 是"卖多少"不是"减多少"，减免额与当笔订单强相关。
            // 秒杀还必须防超发，但决策服务连的是只读账号、物理上写不了库——所以这里**只算钱**，
            // 库存扣减在写平面的 claim 端点（决策 ≠ 提交），决策侧的余量判断只是建议性闸门。
            if (form == BenefitForm.FIXED_PRICE) {
                BigDecimal base = baseAmount(ctx, c);
                BigDecimal off = BenefitMath.fixedPriceDiscount(base, c.getRedPackageAmount());
                // 订单比秒杀价还便宜 / 缺金额 / 作用域基数不可知 → 本活动不适用
                if (off == null) { notApplicable(c, baseCodeOr(base, "price-above-base"), baseUnknownOr(base, "一口价高于作用域金额或缺订单金额")); continue; }
                c.setComputedAmount(off);
                c.setAmountComputed(true);
                continue;
            }

            // 形态判别必须在最前面。漏了它，「打 8 折」会被当成「减 8 元」原样发出去——
            // 而且看起来毫无异常：金额是正数、决策成功、日志干净。
            if (form == BenefitForm.RATIO_ZHE) {
                BigDecimal base = baseAmount(ctx, c);
                BigDecimal off = BenefitMath.ratioDiscount(base, c.getRedPackageAmount(), c.getRedPackageMaxDiscount());
                // 算不出来（没基数 / 折数越界）→ **不给优惠**，而不是给 0 元：
                // 给 0 元会让它以 0 参与 MAX 竞争并可能挤掉别的活动。
                if (off == null) { notApplicable(c, baseCodeOr(base, "bad-ratio"), baseUnknownOr(base, "缺订单金额或折数越界")); continue; }
                c.setComputedAmount(off);
                c.setAmountComputed(true);
                continue;
            }

            c.setComputedAmount(c.getRedPackageAmount());
            c.setAmountComputed(true);
        }
    }

    /**
     * 「这个活动算不出金额」= <b>它不适用</b>，而不是「它减 0 元」。
     *
     * <p>四种形态各自的 fail-closed 分支从前都只是 {@code continue}，注释也写着「不给优惠，不是给 0 元」——
     * 但候选的 {@code eligible} 默认 true、{@code computedAmount} 默认 ZERO，而
     * {@link #merge} 只按 {@code isEligible} 过滤。于是 continue 出来的候选<b>仍是一个合法候选，
     * 只是金额为 0</b>：它会被 MAX 选中（当它是唯一候选时）从而报出 {@code hit=true, amount=0}，
     * 更糟的是在 PRIORITY/MUTEX 下能凭 priority 挤掉一个本可以减 10 元的活动。
     * 契约写在注释里、却没有落到数据结构上，是这个 bug 的全部成因。
     *
     * <p>用 {@link ActivityCandidate#reject} 而不是只清 {@code computedAmount}：
     * 「不适用」和「减 0 元」必须在数据上可区分——阶梯首档 reward=0 是合法的 0 元优惠，
     * 靠金额判别会把它一起误杀。
     *
     * <p><b>注意它会让「唯一候选不适用」走到空决策回退</b>（{@code ActivityQueryService} 的
     * {@code empty-decision}）。这与「候选全被资格条件淘汰」走的是同一条路，不是新增的异常路径。
     */
    private void notApplicable(ActivityCandidate c, String reasonCode, String why) {
        c.reject("本活动不适用：" + why);
        // 「配了但不发」此前在监控上完全不可见：rejectReason 只写在候选对象上，
        // 而热路径 explain=false，它与 trace 两个出口在生产上都不打开。
        // scene 用 "benefit" 标出**阶段**（算额）而不是业务场景——与资格阶段的淘汰区分开，
        // 因为这两者的排查方向完全不同：一个查配置的门槛，一个查入参与形态。
        metrics.reject("benefit", reasonCode);
    }

    /** 基数算不出来时统一归到 out-of-scope，否则用形态自己的原因码。 */
    private static String baseCodeOr(BigDecimal base, String otherwise) {
        return base == null ? "out-of-scope" : otherwise;
    }

    /** 基数为 null 时给出更准确的原因——「算不出基数」和「基数不够」是两种完全不同的排查方向。 */
    private static String baseUnknownOr(BigDecimal base, String otherwise) {
        return base == null ? "作用域基数不可知（活动只圈了部分商品，但请求未带订单行）" : otherwise;
    }

    /**
     * <b>这个活动的钱该算在多少金额上。</b>本包的语义核心。
     *
     * <p>此前一律用 {@code ctx.getOrderAmount()}，于是绑定关系只是个<em>候选筛选器</em>：
     * 一个只绑了 B 的「9.9 一口价」，在「A 5000 元 + B」的车里会算成 {@code 5009.9 − 9.9}，
     * <b>整车按 9.9 成交</b>。判据必须从「订单一共多少钱」换成「<em>本活动的商品</em>一共多少钱」。
     *
     * <p><b>三档，顺序不能反</b>：
     * <ol>
     *   <li><b>作用域未知</b>（{@code scopedSpuIds == null}）→ 整单。手工构造的候选与任何还没接上
     *       作用域的装配路径都走这里，行为与改造前逐字节一致。这是兼容承诺，不是偷懒。</li>
     *   <li><b>作用域覆盖了本次请求的全部 SPU</b> → 整单。此时「整单」与「本活动的商品」本就是同一批东西，
     *       {@code orderAmount} 是完全合法的基数。<b>今天绝大多数流量都落在这一档</b>
     *       （单 SPU 查询、全场券），把它一起 fail-closed 会让线上所有不传订单行的秒杀券/折扣券当场失效——
     *       修一个多发的 bug，换来一个全线不发的 bug。</li>
     *   <li><b>作用域是真子集</b> → 必须靠订单行分摊。<b>拿不到行就是拿不到，返回 null 让调用方淘汰这个候选</b>，
     *       绝不用整单金额顶替：那正是本包要修的那笔钱。</li>
     * </ol>
     *
     * <p><b>已知落差</b>：第 2 档用 {@code orderAmount}、第 3 档用 {@code Σ 作用域行}，两者口径可能不同
     * （运费、平台补贴、已减金额算不算进 orderAmount，入参契约里没有规定）。收敛方向是要求调用方
     * 传订单行、或在契约里写死 orderAmount 的口径，而不是在这里猜。
     *
     * @return 基数；{@code null} 表示<b>算不出来</b>（调用方必须当成「本活动不适用」，而不是「基数为 0」）
     */
    private static BigDecimal baseAmount(ActivityRuleContext ctx, ActivityCandidate c) {
        if (ctx == null) return null;
        java.util.Set<Long> scope = c.getScopedSpuIds();
        if (scope == null) return ctx.getOrderAmount();                    // ① 未知 → 整单（兼容）

        java.util.Set<Long> requested = ctx.requestedSpuIds();
        if (requested.isEmpty() || scope.containsAll(requested)) {
            return ctx.getOrderAmount();                                   // ② 覆盖整单 → 整单
        }
        return BenefitMath.scopedSubtotal(toLines(ctx), scope);            // ③ 真子集 → 作用域小计
    }

    /** 属性袋里的订单行 → 纯数学层的 Line（带 SPU 归属，供作用域过滤）。 */
    private static List<BenefitMath.Line> toLines(ActivityRuleContext ctx) {
        List<BenefitMath.Line> lines = new java.util.ArrayList<>();
        for (SpuDiscountRequest.OrderLine l : ctx.orderLines()) {
            if (l.unitPrice() != null && l.quantity() != null) {
                lines.add(new BenefitMath.Line(l.spuId(), l.unitPrice(), l.quantity()));
            }
        }
        return lines;
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
     *
     * <p><b>出口封顶</b>：无论哪种策略，最终减免额都不得超过订单金额，见 {@link #capToOrderAmount}。
     */
    public ActivityRuleResult merge(ActivityRuleContext ctx, List<ActivityCandidate> candidates,
                                    StackStrategy strategy) {
        return merge(ctx, candidates, strategy, false);
    }

    /**
     * {@code explain=true} 时产出与原 DRL 同文案的 trace，控制台试算的展示不变。
     *
     * <p><b>为什么签名里必须有 {@code ctx}</b>：封顶要拿订单金额比，而订单金额只在上下文里。
     * 曾经的三参重载被刻意删掉而不是保留成「不封顶」的便捷版——留着它，任何一个新调用点
     * 都能在毫无察觉的情况下绕过封顶，而这正是 fail-open 最典型的长法。
     * {@code ctx} 与 {@link #applyLadder} / {@link #computeAmounts} 的首参一致，不是新概念。
     */
    public ActivityRuleResult merge(ActivityRuleContext ctx, List<ActivityCandidate> candidates,
                                    StackStrategy strategy, boolean explain) {
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
            capToOrderAmount(ctx, result, explain);
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
        capToOrderAmount(ctx, result, explain);
        return result;
    }

    /**
     * <b>减免额不得超过订单金额。</b>超出部分截断，并计一次 {@code activity.decision.clamped}。
     *
     * <p>此前这里什么都没有：STACK 是裸累加，出口只判 {@code hitAmount > 0}——**只有下界没有上界**。
     * 三张「满 100 减 50」打在 120 元订单上会返回 150，负的应付金额就这样交给下游订单系统；
     * 单张也一样，一个 50 元红包打在 30 元订单上照发 50。
     *
     * <p><b>截断本身不是目的，计数才是。</b>能触发封顶的配置几乎一定是配错了
     * （满减门槛写反、券面额多打一个零、几张券叠加没设上限），而这类错误在补这个指标之前
     * 在监控上是**全盘绿灯**：回退率 0、耗时正常、命中数只是稍高。所以截断的同时打点 + 打日志，
     * 让「有人配错了」变成一个能被告警发现的事件，而不是月底对账时的一个惊喜。
     *
     * <p><b>订单金额缺省时不封顶</b>：普通红包的面额本就与订单金额无关（{@code AMOUNT} 型不要求
     * 上游传订单金额），此时无从判断是否超发。一律按 0 处理会改掉现有金标语义、把正常决策打没。
     * 这是一条已知的、有意保留的边界——真要收紧应该在<b>入参契约</b>上要求订单金额，而不是在这里猜。
     */
    private void capToOrderAmount(ActivityRuleContext ctx, ActivityRuleResult result, boolean explain) {
        BigDecimal amount = result.getHitAmount();
        if (amount == null) {
            result.setHitAmount(BigDecimal.ZERO);
            return;
        }
        BigDecimal order = ctx == null ? null : ctx.getOrderAmount();
        if (order == null || order.signum() <= 0) return;
        if (amount.compareTo(order) <= 0) return;

        metrics.clamped();
        log.warn("[clamp] 减免额 {} 超过订单金额 {}，已按订单金额封顶（命中活动 {}，策略 {}）——"
                        + "这几乎一定是配置错误，请查该活动的面额/门槛/叠加策略",
                amount, order, result.getHitActivityId(), result.getStrategy());
        result.setHitAmount(order);
        result.setClamped(true);
        if (explain) {
            result.trace("clamped: 减免 " + amount + " 超过订单金额 " + order + "，按订单金额封顶");
        }
    }

    // ================================================================ 第 N 件折

    /**
     * 「第 N 件打 X 折」求值。订单行从属性袋的 {@code orderLines} 取——
     * 它由 {@code DecisionEligibilityService.requestAttributes} 唯一映射表写入。
     *
     * <p>缺行项 / N 非法 / 折数越界 → null（不适用）。这是 fail-closed：
     * 宁可这个活动不生效，也不拿均价算出一个"看起来对"的错金额。
     */
    private static BigDecimal nthDiscount(ActivityRuleContext ctx, ActivityCandidate c) {
        if (ctx == null) return null;
        List<BenefitMath.Line> lines = toLines(ctx);
        if (lines.isEmpty()) return null;

        Integer nth = RandomRangeParser.parseNth(c.getRedPackageRangeAmount());
        if (nth == null) return null;

        // 作用域限定：活动只绑了 B，就不能让车里的 A 替它凑出「第二件」。
        // scope == null（作用域未知）时不限定，与改造前一致。
        return BenefitMath.nthItemDiscount(lines, nth, c.getRedPackageAmount(), c.getScopedSpuIds());
    }

    // ================================================================ 随机红包

    /**
     * 发放方式判别。**未知 code 一律回落固定金额**，与 {@code BenefitForm.of} 的取向一致：
     * 脏数据的表现是「按旧行为发」，而不是「按某种猜出来的方式发」。
     *
     * <p>注意不能直接用 {@code DistributionMode.fromCode}——它对未知 code 抛异常，
     * 那会让一条脏数据打断整批候选的算额。
     */
    private static DistributionMode distributionOf(ActivityCandidate c) {
        Integer code = c.getRedPackageTakeType();
        if (code == null) return DistributionMode.FIXED_AMOUNT;
        return code == DistributionMode.RANDOM_AMOUNT.code()
                ? DistributionMode.RANDOM_AMOUNT
                : DistributionMode.FIXED_AMOUNT;
    }

    /**
     * 抽随机金额。**确定性**：同一 (活动+版本, 用户, 购物车) 永远抽到同一个数，
     * 理由见 {@link BenefitMath#randomAmount}（决策要可重放、可对账、刷新不变价）。
     *
     * <p>购物车指纹用 orderAmount + quantity + spuId 三者拼装——它们是入口契约里
     * 唯一能标识「这一单」的东西（没有订单号）。三者都缺时指纹为 "null|null|null"，
     * 此时同一用户在该活动上恒定拿同一个数；这是缺字段下唯一可确定的行为，
     * 且比"每次不同"更安全。
     *
     * <p><b>指纹的 SPU 段刻意读 {@code randomSeedSpu} 而不是 {@code spuId}</b>：
     * 后者已经从「购物车第一件」改成了「整个 SPU 列表」（作用域改造），
     * 它的 {@code toString()} 从 {@code "990011"} 变成了 {@code "[990011]"}——
     * 而指纹的任何一个字节变化都会让 SHA-256 输出彻底不同，后果是
     * <b>全量随机红包一次性重抽</b>：用户刷新页面金额就变、历史对账全部对不上。
     * {@code randomSeedSpu} 由 {@code DecisionEligibilityService} 专门维持成「第一件」的旧值，
     * 唯一职责就是把这条种子链钉住。改它等于改所有历史金额。
     */
    private static BigDecimal drawRandom(ActivityRuleContext ctx, ActivityCandidate c) {
        RandomRangeParser.Range range = RandomRangeParser.parse(c.getRedPackageRangeAmount());
        if (range == null) return null;

        Object userId = ctx == null ? null : ctx.textAttr("userId");
        String fingerprint = ctx == null ? "null|null|null"
                : canonical(ctx.numberAttr("orderAmount")) + "|"
                  + canonical(ctx.numberAttr("quantity")) + "|"
                  + ctx.textAttr("randomSeedSpu");

        String seed = BenefitMath.randomSeedKey(c.getActivityId(), c.getVersion(), userId, fingerprint);
        return BenefitMath.randomAmount(range.min(), range.max(), seed);
    }

    /**
     * 指纹里的数值段规范化——{@code 100} 与 {@code 100.00} 必须产出同一个字符串。
     *
     * <p>此前这里直接用 {@code textAttr}（即 {@code toString()}），于是客户端把订单金额写成
     * {@code 100} 还是 {@code 100.00}，会得到<b>两个不同的种子、两个不同的红包金额</b>——
     * 而「同一笔订单刷新不变价」正是确定性随机存在的全部理由。一个纯粹的格式差异
     * 就能让用户看到价格跳动，这是这套机制最不该出现的失效方式。
     *
     * <p><b>代价要说清</b>：规范化会让「历史上以非规范格式传参的调用方」拿到与从前不同的金额。
     * 这是一次性的、有意的变更——留着不改则意味着「刷新变价」永久存在。
     */
    private static String canonical(BigDecimal v) {
        return v == null ? "null" : v.stripTrailingZeros().toPlainString();
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
