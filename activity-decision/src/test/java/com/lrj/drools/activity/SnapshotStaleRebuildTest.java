package com.lrj.drools.activity;

import com.lrj.drools.activity.engine.GenerationWarmService;
import com.lrj.drools.activity.persistence.ActivityGenerationEntity;
import com.lrj.drools.activity.persistence.ActivityGenerationRepository;
import com.lrj.drools.activity.snapshot.DecisionSnapshot;
import com.lrj.drools.activity.snapshot.DecisionSnapshotStore;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>快照兜底重建</b>：代际信号漏发时，decision 侧必须能自愈。
 *
 * <p><b>为什么需要兜底而不是只修信号</b>：代际 bump 是「每一个写入口都记得发信号」这条纪律，
 * 而纪律会失手——本仓库已经在它上面栽过一次（下线路径整整少发了一个信号，见 console 的
 * {@code OfflinePropagationTest}）。兜底扫描问的是另一个问题：「我手上这份物料是不是已经旧到不可信了」，
 * 它不依赖任何人记得什么。有它在，「信号漏一次」的后果从<b>永久</b>降为<b>一轮</b>。
 *
 * <p>调度器关掉，手动调 {@code warmDueGenerations()} 做确定性断言。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:snapstale;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.generation-poll.enabled=false",
        // 1ms 阈值：任何已存在的快照在下一轮扫描时都算陈旧，无需等待真实的 60s
        "activity.marketing.snapshot.max-age-ms=1",
        "activity.marketing.seed-demo-data=false"
})
@DisplayName("快照兜底重建：信号漏发也能自愈")
class SnapshotStaleRebuildTest {

    @Autowired GenerationWarmService warmService;
    @Autowired DecisionSnapshotStore store;
    @Autowired ActivityGenerationRepository genRepo;

    @AfterEach
    void clear() {
        store.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("超龄快照被重建，builtAt 前进而代际不变")
    void staleSnapshotIsRebuilt() throws Exception {
        String tenant = "stale-t1";
        String biz = "retail";
        bumpGen(tenant, biz);

        warmService.warmDueGenerations();
        DecisionSnapshot first = store.get(tenant, biz);
        assertNotNull(first, "代际增长后应发布快照");
        Instant firstBuiltAt = first.builtAt();
        long generation = first.generation();

        Thread.sleep(5);

        // 第二轮：代际没动（空扫），但兜底扫描会发现快照超龄并重建
        warmService.warmDueGenerations();
        DecisionSnapshot second = store.get(tenant, biz);

        assertTrue(second.builtAt().isAfter(firstBuiltAt),
                "超龄快照没有被重建 —— 代际信号一旦漏发，陈旧物料将永久留在内存里发钱");
        assertEquals(generation, second.generation(),
                "兜底重建不是一次发布，代际号必须保持不变（否则会与真实发布代际对不上账）");
    }

    @Test
    @DisplayName("快照记录的是数据库里的真实代际号，不是 lastSeen+1")
    void snapshotRecordsRealGeneration() {
        String tenant = "stale-t2";
        String biz = "retail";

        // 两次发布挤在一次轮询间隔内（大促前批量上线很常见）
        assertEquals(1L, bumpGen(tenant, biz));
        assertEquals(2L, bumpGen(tenant, biz));

        warmService.warmDueGenerations();

        assertEquals(2L, store.get(tenant, biz).generation(),
                "快照记下的代际号应为库里的真实值 2。此前这里传的是 lastSeen+1，"
                        + "两次发布挤在一个轮询间隔内就会记成 1，让回滚与代际指标一起对不上账");
    }

    @Test
    @DisplayName("陈旧年龄读数可用（告警的唯一数据源）")
    void oldestAgeIsObservable() {
        assertEquals(-1, store.oldestAgeSeconds(Instant.now()),
                "没有快照时应返回 -1，与『有快照但很新』区分开");

        String tenant = "stale-t3";
        bumpGen(tenant, "retail");
        warmService.warmDueGenerations();

        double age = store.oldestAgeSeconds(Instant.now());
        assertTrue(age >= 0 && age < 60,
                "刚建的快照年龄应接近 0，实际 " + age);
    }

    /** 直接经 genRepo 落/增代际（decision 只读代际、不 bump；bump 服务在 console 模块）。 */
    private long bumpGen(String tenant, String bizLine) {
        ActivityGenerationEntity g = genRepo.findByTenantIdAndBizLine(tenant, bizLine).orElse(null);
        if (g == null) {
            genRepo.save(new ActivityGenerationEntity(tenant, bizLine, 1L, Instant.now()));
            return 1L;
        }
        g.setGeneration(g.getGeneration() + 1);
        g.setUpdatedStime(Instant.now());
        genRepo.save(g);
        return g.getGeneration();
    }
}
