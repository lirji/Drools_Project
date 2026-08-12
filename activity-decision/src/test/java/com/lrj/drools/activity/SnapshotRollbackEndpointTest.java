package com.lrj.drools.activity;

import com.lrj.drools.activity.snapshot.DecisionSnapshot;
import com.lrj.drools.activity.snapshot.DecisionSnapshotBuilder;
import com.lrj.drools.activity.snapshot.DecisionSnapshotStore;
import com.lrj.drools.activity.tenant.TenantContext;
import com.lrj.drools.activity.tenant.TenantContextFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R16 · <b>回滚必须是能从生产按下去的按钮</b>。
 *
 * <p>{@code DecisionSnapshotStore.rollback} 在此之前没有任何生产调用方——全仓只有两个测试调它，
 * 于是「回滚是求值出 bug 时的止损手段」是一张空头支票。本用例钉住两件事：
 * <ol>
 *   <li>{@code POST /decision/v1/snapshot/rollback} 存在、切指针、并按租户隔离（走 {@code X-Tenant-Id}）；</li>
 *   <li><b>同一代际重复 publish 不得占用回滚槽位</b>——预热失败时 {@code GenerationWarmService} 不更新
 *       lastSeen，下一轮会对同代再发一次；若同代重发也移交 previous，回滚就退到出事的这一代本身，等于空转。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:snaprollback;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.generation-poll.enabled=false",
        "activity.marketing.seed-demo-data=false"
})
@DisplayName("快照回滚：生产可达的止损入口 + 回滚槽位不被同代重发挤掉")
class SnapshotRollbackEndpointTest {

    @Autowired MockMvc mvc;
    @Autowired DecisionSnapshotStore store;
    @Autowired DecisionSnapshotBuilder builder;

    @AfterEach
    void clear() {
        store.clear();
        TenantContext.clear();
    }

    private DecisionSnapshot build(String tenant, String bizLine, long generation) {
        return TenantContext.callWith(tenant, () -> builder.build(tenant, bizLine, generation));
    }

    @Test
    @DisplayName("发布两代后回滚 → 200，指针退回上一代")
    void rollbackSwitchesPointerBack() throws Exception {
        String tenant = "snaprb-t1";
        String biz = "retail";
        store.publish(build(tenant, biz, 1L));
        store.publish(build(tenant, biz, 2L));
        assertEquals(2L, store.get(tenant, biz).generation());

        mvc.perform(post("/decision/v1/snapshot/rollback")
                        .header(TenantContextFilter.TENANT_HEADER, tenant)
                        .param("bizLine", biz))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rolledBack").value(true))
                .andExpect(jsonPath("$.fromGeneration").value(2))
                .andExpect(jsonPath("$.toGeneration").value(1));

        assertEquals(1L, store.get(tenant, biz).generation(), "回滚后当前指针应指向第一代");
    }

    @Test
    @DisplayName("没有上一代时返回 409，而不是假装成功")
    void rollbackWithoutPreviousReturns409() throws Exception {
        String tenant = "snaprb-t2";
        String biz = "retail";
        store.publish(build(tenant, biz, 1L));

        mvc.perform(post("/decision/v1/snapshot/rollback")
                        .header(TenantContextFilter.TENANT_HEADER, tenant)
                        .param("bizLine", biz))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.rolledBack").value(false));

        assertEquals(1L, store.get(tenant, biz).generation(), "回滚失败不得动当前指针");
    }

    @Test
    @DisplayName("回滚只影响本租户的桶")
    void rollbackIsTenantScoped() throws Exception {
        String mine = "snaprb-t3";
        String other = "snaprb-t4";
        String biz = "retail";
        store.publish(build(mine, biz, 1L));
        store.publish(build(mine, biz, 2L));
        store.publish(build(other, biz, 1L));
        store.publish(build(other, biz, 2L));

        mvc.perform(post("/decision/v1/snapshot/rollback")
                        .header(TenantContextFilter.TENANT_HEADER, mine)
                        .param("bizLine", biz))
                .andExpect(status().isOk());

        assertEquals(1L, store.get(mine, biz).generation());
        assertEquals(2L, store.get(other, biz).generation(), "别的租户的指针不能被连累");
    }

    @Test
    @DisplayName("同一代际重复 publish 不占回滚槽位（预热失败重试的必然形态）")
    void republishingSameGenerationKeepsRollbackSlot() {
        String tenant = "snaprb-t5";
        String biz = "retail";
        store.publish(build(tenant, biz, 1L));
        store.publish(build(tenant, biz, 2L));

        // 预热在 publish 之后抛异常 → lastSeen 没更新 → 下一轮对同一代际再 publish 一次。
        // 若这次也移交 previous，回滚就只能退到「出事的第 2 代」本身。
        store.publish(build(tenant, biz, 2L));

        assertTrue(store.rollback(tenant, biz), "应仍能回滚");
        assertEquals(1L, store.get(tenant, biz).generation(),
                "回滚必须退到上一个**发布代际**（1），而不是同代重发前的副本（2）");
        assertFalse(store.rollback(tenant, biz), "只保留一代，再回滚一次必须失败");
    }
}
