package com.lrj.drools.activity.engine;

import com.lrj.drools.activity.domain.StackStrategy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 生成各场景的 DRL 文本，运行时交给 {@link ActivityRuleRuntimeService} 用 KieHelper 编译。
 *
 * DRL 里引用的 fact 全是 {@code com.lrj.drools.activity.domain.*}；折扣合并规则直接照抄来源
 * {@code DiscountDbRuleSource.buildDrl} 的语义（MAX / MUTEX / STACK / PRIORITY），只改包名。
 *
 * <p><b>P0-1 通用化</b>：资格约束 / 阶梯闸门改用 Map fact 的方法左值访问器
 * （{@code numberAttr("orderAmount") >= …}），阶梯字段参数化（{@link LadderActivityDef#ladderField()}）。
 * <p><b>P2-21 防注入</b>：所有拼进 DRL 的标识符（activityId、ladderField）过 {@link #ID_PATTERN} 白名单。
 * <p><b>P1-7 trace 构建期开关</b>：{@code explain=false} 时**构建期就不 emit** {@code result.trace(...)}，
 * 而非响应期过滤——大租户大规则集下省掉每 fire 的字符串累积与 GC。
 */
@Component
public class ActivityDrlBuilder {

    private static final String FACT = "com.lrj.drools.activity.domain";

    /** 拼进 DRL 的标识符白名单（activityId / ladderField）。 */
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");

    /** 资格淘汰规则定义：activityId + 翻译好的 Drools 约束（放进 ActivityRuleContext(...) 里）。 */
    public record EligibilityRuleDef(String activityId, String constraint) {}

    /** 阶梯档位：[min, max) 命中给 reward。 */
    public record LadderTier(BigDecimal min, BigDecimal max, BigDecimal reward) {}

    /** 某活动的阶梯配置。{@code ladderField} = 落档比较的 schema 字段 key（电商=orderAmount，出行=completedTrips）。 */
    public record LadderActivityDef(String activityId, List<LadderTier> tiers, String ladderField) {}

    private String header(String scenePkg) {
        return "package " + FACT.replace("domain", "rules.") + scenePkg + ";\n" +
                "import " + FACT + ".ActivityCandidate;\n" +
                "import " + FACT + ".ActivityRuleContext;\n" +
                "import " + FACT + ".ActivityRuleResult;\n" +
                "import " + FACT + ".StackStrategy;\n" +
                "import java.math.BigDecimal;\n" +
                "global " + FACT + ".ActivityRuleResult result;\n\n";
    }

    /** 校验并返回拼进 DRL 的标识符（P2-21）。非法即抛，不静默拼接。 */
    private static String safeId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("非法标识符（须 ^[A-Za-z0-9_]+$，防 DRL 注入）: " + id);
        }
        return id;
    }

    /** trace 行：explain=false 时构建期直接省略。 */
    private static void trace(StringBuilder sb, boolean explain, String traceStmt) {
        if (explain) sb.append(traceStmt);
    }

    // ---------------------------------------------------------------- ELIGIBILITY

    /**
     * 资格场景：为每个"有条件"的活动生成一条淘汰规则
     * （{@code not ActivityRuleContext(<约束>)} → reject），最后低 salience 收集所有 eligible 候选。
     * 没有条件的活动默认通过。
     */
    public String buildEligibilityDrl(List<EligibilityRuleDef> defs, boolean explain) {
        StringBuilder sb = new StringBuilder(header("eligibility"));
        int i = 0;
        for (EligibilityRuleDef def : defs) {
            if (def.constraint() == null || def.constraint().isBlank()) continue;
            String id = safeId(def.activityId());
            sb.append("rule \"elig_reject_").append(i++).append("\"\n")
                    .append("    salience 10\n")
                    .append("    when\n")
                    .append("        $c : ActivityCandidate( activityId == \"").append(id).append("\", eligible == true )\n")
                    .append("        not ActivityRuleContext( ").append(def.constraint()).append(" )\n")
                    .append("    then\n")
                    .append("        $c.reject(\"不满足资格条件\");\n");
            trace(sb, explain, "        result.trace(\"eligibility reject: " + id + "\");\n");
            sb.append("end\n\n");
        }
        sb.append("rule \"elig_collect\"\n")
                .append("    salience -100\n")
                .append("    when\n")
                .append("        $c : ActivityCandidate( eligible == true )\n")
                .append("    then\n")
                .append("        result.addEligible($c);\n");
        trace(sb, explain, "        result.trace(\"eligible: \" + $c.getActivityId());\n");
        sb.append("end\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- DISCOUNT

    /**
     * 折扣合并场景。先算每个候选的 computedAmount（= redPackageAmount），再按策略挑选/累加。
     * 照抄来源 {@code DiscountDbRuleSource} 语义。
     */
    public String buildDiscountDrl(StackStrategy strategy, boolean explain) {
        StringBuilder sb = new StringBuilder(header("discount"));
        // 共享：算额规则（把红包金额落到 computedAmount，amountComputed 防重复触发）
        sb.append("rule \"discount-compute-amount\"\n")
                .append("    salience 100\n")
                .append("    when\n")
                .append("        $c : ActivityCandidate( eligible == true, amountComputed == false, redPackageAmount != null )\n")
                .append("    then\n")
                .append("        modify($c) { setComputedAmount($c.getRedPackageAmount()), setAmountComputed(true) }\n")
                .append("end\n\n");

        switch (strategy) {
            case STACK -> appendStackRules(sb, explain);
            case MUTEX, PRIORITY -> appendPriorityRules(sb, strategy, explain);
            default -> appendMaxRules(sb, explain);
        }
        return sb.toString();
    }

    private void appendMaxRules(StringBuilder sb, boolean explain) {
        sb.append("rule \"discount-pick-max\"\n")
                .append("    salience 10\n")
                .append("    when\n")
                .append("        $c : ActivityCandidate( eligible == true )\n")
                .append("        not ActivityCandidate( eligible == true, computedAmount > $c.computedAmount )\n")
                .append("    then\n")
                .append("        result.setStrategy(StackStrategy.MAX);\n")
                .append("        result.hit($c);\n");
        trace(sb, explain, "        result.trace(\"hit by MAX: \" + $c.getActivityId() + \" amount=\" + $c.getComputedAmount());\n");
        sb.append("end\n");
    }

    private void appendPriorityRules(StringBuilder sb, StackStrategy strategy, boolean explain) {
        String name = strategy.name().toLowerCase();
        sb.append("rule \"discount-pick-").append(name).append("\"\n")
                .append("    salience 10\n")
                .append("    when\n")
                .append("        $c : ActivityCandidate( eligible == true )\n")
                .append("        not ActivityCandidate( eligible == true,\n")
                .append("                ( priority < $c.priority\n")
                .append("                  || ( priority == $c.priority && computedAmount > $c.computedAmount ) ) )\n")
                .append("    then\n")
                .append("        result.setStrategy(StackStrategy.").append(strategy.name()).append(");\n")
                .append("        result.hit($c);\n");
        trace(sb, explain, "        result.trace(\"hit by " + strategy.name()
                + ": \" + $c.getActivityId() + \" priority=\" + $c.getPriority() + \" amount=\" + $c.getComputedAmount());\n");
        sb.append("end\n");
    }

    private void appendStackRules(StringBuilder sb, boolean explain) {
        sb.append("rule \"discount-stack-sum\"\n")
                .append("    salience 20\n")
                .append("    when\n")
                .append("        $total : BigDecimal() from accumulate(\n")
                .append("                ActivityCandidate( eligible == true, $amt : computedAmount != null ),\n")
                .append("                init( BigDecimal sum = BigDecimal.ZERO; ),\n")
                .append("                action( sum = sum.add($amt); ),\n")
                .append("                result( sum ) )\n")
                .append("    then\n")
                .append("        result.setStrategy(StackStrategy.STACK);\n")
                .append("        result.setHitAmount($total);\n");
        trace(sb, explain, "        result.trace(\"stack sum amount=\" + $total);\n");
        sb.append("end\n\n");
        sb.append("rule \"discount-stack-main\"\n")
                .append("    salience 10\n")
                .append("    when\n")
                .append("        $c : ActivityCandidate( eligible == true )\n")
                .append("        not ActivityCandidate( eligible == true,\n")
                .append("                ( priority < $c.priority\n")
                .append("                  || ( priority == $c.priority && computedAmount > $c.computedAmount ) ) )\n")
                .append("    then\n")
                .append("        result.setHitActivityId($c.getActivityId());\n")
                .append("        result.setHitActivityName($c.getActivityName());\n");
        trace(sb, explain, "        result.trace(\"stack main activityId=\" + $c.getActivityId());\n");
        sb.append("end\n");
    }

    // ---------------------------------------------------------------- LADDER

    /** 阶梯场景：每个活动的每个档位一条规则，命中即 setComputedAmount + hit。落档字段参数化（P2-18）。 */
    public String buildLadderDrl(List<LadderActivityDef> defs, boolean explain) {
        StringBuilder sb = new StringBuilder(header("ladder"));
        int i = 0;
        for (LadderActivityDef def : defs) {
            String id = safeId(def.activityId());
            String field = safeId(def.ladderField());
            String acc = "numberAttr(\"" + field + "\")";
            for (LadderTier tier : def.tiers()) {
                sb.append("rule \"ladder_").append(i++).append("\"\n")
                        .append("    when\n")
                        .append("        $ctx : ActivityRuleContext( ").append(acc).append(" != null && ")
                        .append(acc).append(" >= ").append(tier.min().toPlainString())
                        .append(" && ").append(acc).append(" < ").append(tier.max().toPlainString()).append(" )\n")
                        .append("        $c : ActivityCandidate( activityId == \"").append(id).append("\", eligible == true )\n")
                        .append("    then\n")
                        .append("        $c.setComputedAmount(new BigDecimal(\"").append(tier.reward().toPlainString()).append("\"));\n")
                        .append("        result.hit($c);\n");
                trace(sb, explain, "        result.trace(\"ladder hit " + id
                        + ": [" + tier.min().toPlainString() + "," + tier.max().toPlainString()
                        + ") -> " + tier.reward().toPlainString() + "\");\n");
                sb.append("end\n\n");
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- GIFT

    /** 买赠场景：保留有奖品的 eligible 候选，把其奖品汇总到 result。 */
    public String buildGiftDrl(boolean explain) {
        StringBuilder sb = new StringBuilder(header("gift"));
        sb.append("rule \"gift-collect\"\n")
                .append("    when\n")
                .append("        $c : ActivityCandidate( eligible == true, gifts != null, gifts.size() > 0 )\n")
                .append("    then\n")
                .append("        result.addEligible($c);\n")
                .append("        result.getGifts().addAll($c.getGifts());\n");
        trace(sb, explain, "        result.trace(\"gift activity: \" + $c.getActivityId());\n");
        sb.append("end\n");
        return sb.toString();
    }
}
