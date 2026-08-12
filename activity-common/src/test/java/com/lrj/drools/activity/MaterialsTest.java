package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCandidate;
import com.lrj.drools.activity.domain.DecisionProvenance;
import com.lrj.drools.activity.domain.OfferSpec;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.service.Materials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取数层出参 {@link Materials} 的**取值规则钉子**。
 *
 * <p>这里钉的两条都属于「今天没有正确答案，但必须是确定行为」那一类：
 * 跨业务线时 {@code bizLine()} 取谁、多桶合并时代际与策略怎么收敛。
 * 没有测试的话，它们会在下一次重构里被"顺手"改成另一个同样说得通的取值，
 * 而表现是同一次请求换了一条合并策略——不报错、不回退、日志干净。
 */
@DisplayName("Materials：业务线取值与多桶合并")
class MaterialsTest {

    @Test
    @DisplayName("跨业务线时 bizLine 取 activityId 最小者所属的业务线（与列表顺序无关）")
    void bizLineComesFromSmallestActivityId() {
        ActivityCandidate travel = candidate("ACT-B", "travel");
        ActivityCandidate ecom = candidate("ACT-A", "ecom");

        // 两种入参顺序必须给出同一条业务线：走库侧跟着 SQL 返回序、快照侧跟着 JDK SALT，
        // 两边的天然顺序都不可靠，取值规则不能依赖它。
        assertThat(new Materials(List.of(travel, ecom), List.of(), Map.of()).bizLine()).isEqualTo("ecom");
        assertThat(new Materials(List.of(ecom, travel), List.of(), Map.of()).bizLine()).isEqualTo("ecom");
    }

    @Test
    @DisplayName("候选为空时 bizLine 为 null，策略退默认 MAX")
    void emptyMaterialsHaveNoBizLine() {
        assertThat(Materials.empty().bizLine()).isNull();
        assertThat(Materials.empty().strategy()).isEqualTo(StackStrategy.MAX);
        assertThat(Materials.empty().provenance().source()).isEqualTo(DecisionProvenance.SOURCE_DB);
    }

    @Test
    @DisplayName("activityId 为 null 的候选排最后，不会抢走 bizLine")
    void nullActivityIdNeverLeads() {
        ActivityCandidate unknown = candidate(null, "unknown");
        ActivityCandidate ecom = candidate("ACT-Z", "ecom");

        assertThat(new Materials(List.of(unknown, ecom), List.of(), Map.of()).bizLine()).isEqualTo("ecom");
    }

    @Test
    @DisplayName("构造器不定序：定序只发生在 ordered()")
    void constructorDoesNotSort() {
        ActivityCandidate b = candidate("ACT-B", "ecom");
        ActivityCandidate a = candidate("ACT-A", "ecom");

        // 打平时 pickByAmount/pickByPriority 是严格 >（先到先得），构造器偷偷定序会让一批断言翻面。
        Materials raw = new Materials(List.of(b, a), List.of(), Map.of());
        assertThat(raw.candidates()).containsExactly(b, a);
        assertThat(raw.ordered().candidates()).containsExactly(a, b);
    }

    @Test
    @DisplayName("多桶合并：代际取最小、桶数取份数、策略取 bizLine 所属那个桶")
    void mergeTakesMinGenerationAndLeadBucketStrategy() {
        Materials ecom = Materials.snapshotBucket(
                List.of(candidate("ACT-A", "ecom")), List.of(), Map.of(), 7L, StackStrategy.STACK);
        Materials travel = Materials.snapshotBucket(
                List.of(candidate("ACT-B", "travel")), List.of(), Map.of(), 3L, StackStrategy.PRIORITY);
        // 对本次决策没有贡献的桶也要参与「代际取最小」与桶数统计
        Materials idle = Materials.snapshotBucket(
                List.of(), List.of(), Map.of(), 2L, StackStrategy.MUTEX);

        Materials merged = Materials.merge(List.of(travel, ecom, idle));

        assertThat(merged.candidates()).hasSize(2);
        assertThat(merged.bizLine()).isEqualTo("ecom");
        assertThat(merged.strategy()).isEqualTo(StackStrategy.STACK);
        assertThat(merged.provenance().source()).isEqualTo(DecisionProvenance.SOURCE_SNAPSHOT);
        assertThat(merged.provenance().generation()).isEqualTo(2L);
        assertThat(merged.provenance().buckets()).isEqualTo(3);
    }

    private static ActivityCandidate candidate(String activityId, String bizLine) {
        return new ActivityCandidate(OfferSpec.builder()
                .activityId(activityId)
                .activityName(activityId)
                .bizLine(bizLine)
                .version(1)
                .build());
    }
}
