package com.lrj.drools.activity;

import com.lrj.drools.activity.persistence.ActivityGenerationRepository;
import com.lrj.drools.activity.service.GenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M1.4 · console 侧发布代际 bump（{@link GenerationService}）覆盖。物理拆分后 bump（写）在 console 模块、
 * 轮询预热（读）在 decision 模块（见 decision 的 {@code GenerationWarmPollerTest}）。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:genbump;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-catalog-data=false"
})
class GenerationBumpTest {

    @Autowired GenerationService generationService;
    @Autowired ActivityGenerationRepository genRepo;

    @Test
    void bumpFirstIsOne_thenIncrements() {
        assertEquals(1L, generationService.bump("gb-t1", "retail"), "首次发布代际=1");
        assertEquals(2L, generationService.bump("gb-t1", "retail"), "再发布 +1");
        assertEquals(2L, genRepo.findByTenantIdAndBizLine("gb-t1", "retail").orElseThrow().getGeneration(),
                "落库代际=2");
    }

    @Test
    void bumpIsolatedPerBizLine() {
        generationService.bump("gb-t2", "retail");
        generationService.bump("gb-t2", "wholesale");
        assertEquals(1L, genRepo.findByTenantIdAndBizLine("gb-t2", "retail").orElseThrow().getGeneration());
        assertEquals(1L, genRepo.findByTenantIdAndBizLine("gb-t2", "wholesale").orElseThrow().getGeneration(),
                "不同 bizLine 各自独立代际");
    }

    @Test
    void concurrentBumpsOnSameBizLineAreNotLost() throws Exception {
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return generationService.bump("gb-concurrent", "retail");
                }));
            }
            ready.await();
            start.countDown();
            for (Future<Long> result : results) {
                result.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(workers,
                genRepo.findByTenantIdAndBizLine("gb-concurrent", "retail").orElseThrow().getGeneration(),
                "同一业务线的并发发布必须逐次递增，不能丢失 decision 重建信号");
    }
}
