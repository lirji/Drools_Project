package com.lrj.drools.activity.service;

import com.lrj.drools.activity.persistence.ActivityGenerationEntity;
import com.lrj.drools.activity.persistence.ActivityGenerationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final GenerationWriteStore writeStore;

    public GenerationService(ActivityGenerationRepository genRepo, GenerationWriteStore writeStore) {
        this.genRepo = genRepo;
        this.writeStore = writeStore;
    }

    /**
     * 把 {@code (tenant, bizLine)} 的发布代际 +1；行不存在则建 gen=1。事务默认复用调用方
     * （发布上线）的事务，与业务落库同提交/同回滚；直接调用时则自行开启事务。
     *
     * <p>必须使用数据库原子 upsert：如果两个不同活动同时改变同一业务线，普通读改写可能都把 N 写成 N+1；
     * decision 若恰好在两次提交之间预热，就会永久错过后一次提交的重建信号。
     */
    @Transactional
    public long bump(String tenant, String bizLine) {
        Instant now = Instant.now();
        long generation = writeStore.upsertAndIncrement(tenant, bizLine, now);
        log.info("[generation] 发布代际更新 tenant={} bizLine={} generation={}", tenant, bizLine, generation);
        return generation;
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
