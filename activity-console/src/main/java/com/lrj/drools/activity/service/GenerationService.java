package com.lrj.drools.activity.service;

import com.lrj.drools.activity.persistence.ActivityGenerationEntity;
import com.lrj.drools.activity.persistence.ActivityGenerationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * M1.4 · 发布代际 bump。console 侧写平面在**发布(上线)**时调用，把 {@code (tenant, bizLine)} 的 generation +1，
 * 作为 decision 侧轮询预热的传播信号（进程内直调 {@code warmAsync} 之外的第二条、可跨进程的路径）。
 *
 * <p>调用发生在 {@link ActivityMarketingService#changeStatus} 的 {@code @Transactional} 内（经 {@link ArtifactService#warmOnPublish}），
 * 故 bump 与"活动置 ONLINE"同事务提交——poller 只会在发布真正落库后才看到新代际（不会预热到未提交的规则）。
 */
@Service
public class GenerationService {

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

    private final ActivityGenerationRepository genRepo;

    public GenerationService(ActivityGenerationRepository genRepo) {
        this.genRepo = genRepo;
    }

    /**
     * 把 {@code (tenant, bizLine)} 的发布代际 +1；行不存在则建 gen=1。无独立 {@code @Transactional}——
     * 复用调用方（发布上线）的事务，与业务落库同提交/同回滚。
     *
     * <p>generation 只是**变更信号**（poller 一旦见到增长就预热该 (tenant,bizLine) 的<b>全部</b> ACTIVE artifact），
     * 不是每次发布必须精确 +1 的计数。故用读改写即可：并发发布即便丢一次自增，最终值仍 ≥N+1、仍触发一次覆盖两者的预热，语义正确。
     */
    public long bump(String tenant, String bizLine) {
        Instant now = Instant.now();
        ActivityGenerationEntity g = genRepo.findByTenantIdAndBizLine(tenant, bizLine).orElse(null);
        if (g == null) {
            genRepo.save(new ActivityGenerationEntity(tenant, bizLine, 1L, now));
            log.info("[generation] 首次发布代际 tenant={} bizLine={} generation=1", tenant, bizLine);
            return 1L;
        }
        g.setGeneration(g.getGeneration() + 1);
        g.setUpdatedStime(now);
        genRepo.save(g);
        log.info("[generation] 发布代际 +1 tenant={} bizLine={} generation={}", tenant, bizLine, g.getGeneration());
        return g.getGeneration();
    }

    /**
     * 库里当前的发布代际。<b>没有这个读口时，决策响应里回显的 generation 是个装饰数字</b>——
     * 运营看到「generation=7」无从判断自己刚发布的那次进没进去，因为没有参照物。
     *
     * <p>行不存在返回 0：语义是「这条业务线还没发布过任何东西」，与「代际是 0」不冲突
     * （{@link #bump} 首次建行给的是 1，代际永远从 1 起）。
     */
    public long current(String tenant, String bizLine) {
        return genRepo.findByTenantIdAndBizLine(tenant, bizLine)
                .map(ActivityGenerationEntity::getGeneration)
                .orElse(0L);
    }
}
