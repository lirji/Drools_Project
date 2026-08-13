package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.OfferSpec;
import com.lrj.drools.activity.domain.StackStrategy;
import com.lrj.drools.activity.snapshot.DecisionSnapshot;
import com.lrj.drools.activity.snapshot.DecisionSnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空壳桶淘汰：**能回收垃圾，且绝不动到回滚目标**。
 *
 * <p>桶是按 {@code (tenant, bizLine)} 惰性创建、只增不减的，而兜底重建每轮把所有桶重建一遍
 * （每桶约 7 次查询）。短生命周期租户（按租户切分的集成测试、临时压测线）会让轮询一轮越来越久，
 * 而<b>轮询变慢直接拉长发布传播的延迟</b>——这是它在生产上真正咬人的方式，不是内存。
 *
 * <p>这个测试要钉的是淘汰的**边界**，而不是「能删掉」：删得太狠会把止损按钮删掉。
 */
@DisplayName("快照空壳桶淘汰")
class SnapshotEmptyBucketEvictionTest {

    private static final Instant T0 = Instant.parse("2026-08-13T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);

    private DecisionSnapshotStore store;

    @BeforeEach
    void fresh() {
        store = new DecisionSnapshotStore();
    }

    /** @param activityCount 0 = 空桶 */
    private static DecisionSnapshot snap(String bizLine, long generation, Instant builtAt, int activityCount) {
        Map<Long, Set<String>> bySpu = new java.util.LinkedHashMap<>();
        Map<String, OfferSpec> specs = new java.util.LinkedHashMap<>();
        for (int i = 0; i < activityCount; i++) {
            String id = bizLine + "-ACT" + i;
            specs.put(id, OfferSpec.builder().activityId(id).bizLine(bizLine).build());
            bySpu.put(900_000L + i, Set.of(id));
        }
        return new DecisionSnapshot("t1", bizLine, generation, builtAt,
                bySpu, specs, Map.of(), Map.of(), StackStrategy.MAX);
    }

    @Nested
    @DisplayName("该回收的")
    class Evicts {

        @Test
        @DisplayName("连续空置超过 TTL 的桶被回收")
        void emptyBucketPastTtlIsEvicted() {
            store.publish(snap("dead", 1, T0, 0));
            assertEquals(1, store.size());

            assertEquals(1, store.evictEmpty(T0.plus(TTL).plusSeconds(1), TTL));
            assertEquals(0, store.size(), "连续空置超过 TTL 的桶应被回收");
        }

        @Test
        @DisplayName("兜底重建刷新 builtAt 不会重置空置起点 —— 否则永远淘汰不掉")
        void fallbackRebuildDoesNotResetTheClock() {
            store.publish(snap("dead", 1, T0, 0));
            // 模拟兜底重建：每 60 秒把同一个空桶重建一遍，builtAt 一路往前推
            for (int minute = 1; minute <= 40; minute++) {
                store.refresh(snap("dead", 1, T0.plusSeconds(minute * 60L), 0));
            }
            assertEquals(1, store.evictEmpty(T0.plusSeconds(41 * 60L), TTL),
                    "空置起点被 refresh 重置了：兜底重建每轮都刷新 builtAt，"
                            + "若拿 builtAt 当空置起点，桶永远达不到 TTL、永远淘汰不掉——"
                            + "而那正是这个功能要解决的问题");
        }
    }

    @Nested
    @DisplayName("绝不能回收的")
    class Keeps {

        @Test
        @DisplayName("还没到 TTL 的空桶留着 —— 两次发布之间的正常空窗不该被回收")
        void emptyBucketWithinTtlSurvives() {
            store.publish(snap("gap", 1, T0, 0));
            assertEquals(0, store.evictEmpty(T0.plusSeconds(TTL.toSeconds() - 1), TTL));
            assertEquals(1, store.size());
        }

        @Test
        @DisplayName("非空桶永不回收")
        void nonEmptyBucketSurvivesForever() {
            store.publish(snap("live", 1, T0, 3));
            assertEquals(0, store.evictEmpty(T0.plusSeconds(86_400), TTL));
            assertNotNull(store.get("t1", "live"));
        }

        /**
         * <b>本测试类存在的首要理由。</b>「运营误把一条业务线的活动全下线了」——当前代重建成 0 个活动，
         * 而回滚槽位里还留着那批活动。那正是止损按钮唯一该被按下的时刻；
         * 若此时把桶当空壳回收掉，等于<b>在最需要回滚的那一刻把回滚目标删了</b>。
         */
        @Test
        @DisplayName("当前代空、但回滚槽位有活动 → 不回收（否则删掉的正是止损按钮）")
        void bucketWhoseRollbackTargetStillHasActivitiesIsNeverEvicted() {
            store.publish(snap("oops", 1, T0, 5));                       // 5 个活动在线
            store.publish(snap("oops", 2, T0.plusSeconds(10), 0));       // 全被下线 → 当前代空、上一代留着 5 个

            assertEquals(0, store.evictEmpty(T0.plusSeconds(86_400), TTL),
                    "当前代虽空，但 previous 里还有 5 个活动 —— 这个桶是回滚目标，绝不能回收");
            assertTrue(store.rollback("t1", "oops"), "回滚必须仍然可用");
            assertEquals(5, store.get("t1", "oops").activityCount(), "回滚后应拿回那 5 个活动");
        }

        @Test
        @DisplayName("回滚之后若两代都空，才重新开始计时")
        void afterRollbackToAnEmptyGenerationTheClockRestarts() {
            store.publish(snap("both-empty", 1, T0, 0));
            store.publish(snap("both-empty", 2, T0.plusSeconds(10), 0));
            assertTrue(store.rollback("t1", "both-empty"));

            // 回滚把 previous 清空、current 换成第 1 代（也是空的）→ 从回滚那一刻重新计时
            assertEquals(0, store.evictEmpty(T0.plusSeconds(60), TTL), "刚回滚完不该立刻被回收");
            assertEquals(1, store.evictEmpty(T0.plus(TTL).plusSeconds(120), TTL));
        }
    }

    @Nested
    @DisplayName("对决策的影响")
    class DecisionImpact {

        /**
         * 淘汰能成立的全部前提：空桶对决策的贡献<b>恒为零</b>——不是「影响很小」，是数学上为零。
         * 所以回收前后，该租户能被决策看到的活动集合必须一模一样。
         */
        @Test
        @DisplayName("回收空桶不改变该租户能被决策看到的活动集合")
        void evictingEmptyBucketsDoesNotChangeVisibleActivities() {
            store.publish(snap("live", 1, T0, 2));
            store.publish(snap("dead-a", 1, T0, 0));
            store.publish(snap("dead-b", 1, T0, 0));

            int before = store.forTenant("t1").stream().mapToInt(DecisionSnapshot::activityCount).sum();
            assertEquals(2, store.evictEmpty(T0.plus(TTL).plusSeconds(1), TTL));
            int after = store.forTenant("t1").stream().mapToInt(DecisionSnapshot::activityCount).sum();

            assertEquals(before, after, "回收空桶改变了可见活动数——那说明被回收的桶里其实有东西");
            assertEquals(1, store.forTenant("t1").size(), "只该剩下有活动的那个桶");
            assertFalse(store.forTenant("t1").isEmpty(),
                    "还有非空桶时不能把租户清空（那会让决策回落走库、provenance.source 翻成 db）");
        }
    }
}
