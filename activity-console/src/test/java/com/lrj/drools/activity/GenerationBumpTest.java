package com.lrj.drools.activity;

import com.lrj.drools.activity.persistence.ActivityGenerationRepository;
import com.lrj.drools.activity.service.GenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

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
        "activity.marketing.seed-demo-data=false"
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
}
