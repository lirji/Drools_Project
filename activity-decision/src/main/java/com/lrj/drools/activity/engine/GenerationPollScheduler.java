package com.lrj.drools.activity.engine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * M1.4 · 发布代际轮询触发器。把 {@link GenerationWarmService#warmDueGenerations()} 挂到固定延迟调度上。
 *
 * <p><b>与预热逻辑分离</b>：调度（本类）与预热逻辑（{@link GenerationWarmService}）拆开——本类由
 * {@code activity.marketing.generation-poll.enabled}（默认 true）门控，测试可关掉调度、手动调 {@code warmDueGenerations()}
 * 做确定性断言，而 {@link GenerationWarmService} 始终在容器里可注入。关掉时 {@link EnableScheduling} 也随之不激活
 * （本项目当前无其它 {@code @Scheduled} 任务）。
 *
 * <p>物理拆分后本触发器只需在 <b>decision</b> 角色进程启用；console 角色可关（console 只 bump、不预热）。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "activity.marketing.generation-poll.enabled", havingValue = "true", matchIfMissing = true)
public class GenerationPollScheduler {

    private final GenerationWarmService warmService;

    public GenerationPollScheduler(GenerationWarmService warmService) {
        this.warmService = warmService;
    }

    @Scheduled(fixedDelayString = "${activity.marketing.generation-poll.interval-ms:3000}",
            initialDelayString = "${activity.marketing.generation-poll.interval-ms:3000}")
    public void poll() {
        warmService.warmDueGenerations();
    }
}
