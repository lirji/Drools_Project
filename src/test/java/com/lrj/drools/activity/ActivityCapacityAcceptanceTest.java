package com.lrj.drools.activity;

import com.lrj.drools.activity.engine.ActivityDrlBuilder;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderActivityDef;
import com.lrj.drools.activity.engine.ActivityDrlBuilder.LadderTier;
import com.lrj.drools.activity.engine.ActivityRuleRuntimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.internal.utils.KieHelper;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-5 验收（延后项兑现）：① 目标规模负载下**常驻堆 ≈ 容量公式预测**（模型可外推）；
 * ② 淘汰 churn 下 **classloader/Metaspace 回收**（不随 churn 单调涨 = 无泄漏）。
 *
 * <p>gated `-Dsizing=true`（与 {@link ActivityKieBaseSizingTest} 同闸），不进常规 `./mvnw test`；靠 GC-delta 采样，
 * 建议 `-DargLine="-Xmx2g -XX:+UseG1GC -XX:MaxMetaspaceSize=512m"`。绝对值随机器波动，故断言用**宽松倍数带**——
 * 验证的是「量级对 + 线性外推 + 无泄漏」，不是精确数字。
 */
class ActivityCapacityAcceptanceTest {

    private final ActivityDrlBuilder builder = new ActivityDrlBuilder();

    /** ① 负载：N 租户各 1 资格(10 规则)+1 阶梯(20 档) KieBase，常驻堆+Metaspace ≈ **生产 weigher 公式**预测（0.5×~2.5× 带内）。 */
    @Test
    @EnabledIfSystemProperty(named = "sizing", matches = "true")
    void residentFootprintMatchesBudgetFormula() {
        final int tenants = 20, eligRules = 10, ladderTiers = 20;
        warmupInfra();

        gc();
        long heap0 = usedHeap(), meta0 = usedMeta();
        List<KieBase> fleet = new ArrayList<>();
        long predictedKb = 0;
        int totalRules = 0;
        for (int t = 0; t < tenants; t++) {
            String elig = eligDrl("t" + t, eligRules), ladder = ladderDrl("t" + t, ladderTiers);
            fleet.add(compile(elig));
            fleet.add(compile(ladder));
            // 预测用生产同款 footprintKb（260 + 37×生成规则数），与 weigher 一致
            predictedKb += ActivityRuleRuntimeService.footprintKb(elig) + ActivityRuleRuntimeService.footprintKb(ladder);
            totalRules += eligRules + ladderTiers;
        }
        gc();
        long measuredKb = (usedHeap() - heap0 + usedMeta() - meta0) / 1024;

        System.out.printf("%n[P0-5 负载] KieBase=%d, 规则=%d → 预测=%d KB(~%dMB), 实测=%d KB(~%dMB), 比=%.2f%n",
                fleet.size(), totalRules, predictedKb, predictedKb / 1024, measuredKb, measuredKb / 1024,
                measuredKb / (double) predictedKb);

        assertTrue(fleet.size() == tenants * 2, "fleet 建全");
        double ratio = measuredKb / (double) predictedKb;
        assertTrue(ratio > 0.5 && ratio < 2.5,
                "常驻足迹应在公式预测的 0.5×~2.5×（验证量级+线性外推）：ratio=" + ratio);
        fleet.clear();
    }

    /** ② churn：反复建/弃 KieBase，Metaspace 不随轮次单调涨（classloader 被回收）。 */
    @Test
    @EnabledIfSystemProperty(named = "sizing", matches = "true")
    void metaspaceReclaimedUnderChurn() {
        warmupInfra();
        final int cycles = 12, perCycle = 30, tiers = 20;

        long metaAfterFirst = 0, metaAfterLast = 0;
        for (int c = 0; c < cycles; c++) {
            List<KieBase> batch = new ArrayList<>(perCycle);
            for (int i = 0; i < perCycle; i++) {
                batch.add(compile(ladderDrl("churn" + c + "_" + i, tiers))); // 每个不同 → 独立 classloader
            }
            batch.clear();     // 释放 → classloader 可回收
            gc();
            long meta = usedMeta();
            if (c == 0) metaAfterFirst = meta;
            if (c == cycles - 1) metaAfterLast = meta;
        }

        long growthKb = (metaAfterLast - metaAfterFirst) / 1024;
        // 若 classloader 泄漏：每轮 30×(60+12×20)=~9MB 不回收 × 11 轮 ≈ 100MB+。回收正常则近乎持平。
        System.out.printf("%n[P0-5 churn] %d 轮 × %d KieBase：Metaspace 首轮=%dKB 末轮=%dKB 增长=%dKB%n",
                cycles, perCycle, metaAfterFirst / 1024, metaAfterLast / 1024, growthKb);
        assertTrue(growthKb < 20_000,
                "churn 下 Metaspace 增长应远小于泄漏量（<20MB）——证 classloader 被回收：growth=" + growthKb + "KB");
    }

    // ---- helpers ----
    private void warmupInfra() {
        for (int i = 0; i < 3; i++) compile(ladderDrl("warm" + i, 10)); // 共享 Drools 基础设施类先落 Metaspace
    }

    private KieBase compile(String drl) {
        KieHelper h = new KieHelper();
        h.addContent(drl, ResourceType.DRL);
        return h.build();
    }

    private String eligDrl(String base, int rules) {
        List<ActivityDrlBuilder.EligibilityRuleDef> defs = new ArrayList<>();
        for (int i = 0; i < rules; i++) {
            defs.add(new ActivityDrlBuilder.EligibilityRuleDef(
                    base + "_" + i, "numberAttr(\"orderAmount\") != null && numberAttr(\"orderAmount\") >= " + (i + 1)));
        }
        return builder.buildEligibilityDrl(defs, false);
    }

    private String ladderDrl(String actId, int tiers) {
        List<LadderTier> t = new ArrayList<>(tiers);
        for (int i = 0; i < tiers; i++) {
            t.add(new LadderTier(new BigDecimal(i * 100), new BigDecimal((i + 1) * 100), new BigDecimal(i + 1)));
        }
        return builder.buildLadderDrl(List.of(new LadderActivityDef(actId, t, "orderAmount")), false);
    }

    private static long usedHeap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }

    private static long usedMeta() {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if ("Metaspace".equals(p.getName())) return p.getUsage().getUsed();
        }
        return 0;
    }

    private static void gc() {
        for (int i = 0; i < 5; i++) {
            System.gc();
            try { Thread.sleep(80); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
