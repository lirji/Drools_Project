package com.lrj.drools.activity.engine;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 生成各场景的 DRL 文本，运行时交给 {@link ActivityRuleRuntimeService} 用 KieHelper 编译。
 *
 * DRL 里引用的 fact 全是 {@code com.lrj.drools.activity.domain.*}。D1 追认（2026-08-10）后
 * 生产只剩买赠一个 DRL 场景；eligibility DRL 仅用于写平面预览/artifact 的编译校验，
 * ladder DRL 仅作容量/预热测试的负载生成器（它们不再被任何 eval 调用）。
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
                "import com.lrj.drools.activity.engine.BenefitMath;\n" +
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
    //
    // D1 追认（2026-08-10）：buildDiscountDrl（算额 + MAX/MUTEX/PRIORITY/STACK 合并的 DRL 版本）已删——
    // 生产合并固定走 BenefitEvaluator.merge()，运行时侧的 evalDiscount 已一并退役，这里没有调用方了。
    // 取整/封顶的单一权威仍是 BenefitMath（当年两条路共用它防漂移；现在只剩一条路，更没有漂移面）。

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
