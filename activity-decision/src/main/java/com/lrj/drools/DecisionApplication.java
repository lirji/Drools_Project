package com.lrj.drools;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * M2.1 · 决策服务（decision-svc，8082）主类。只读决策热路径 + 发布代际轮询预热。
 *
 * <p>classpath = activity-common(共享域/引擎/持久化/租户 + ActivityQueryService) + 本模块决策面
 * (DecisionPlaneController + GenerationWarmService/PollScheduler)。**不依赖 drools-lab**——无 Step1-18、
 * 无 DroolsConfig(classpath KieContainer)、无 kie-ci/kie-dmn/decisiontables 重依赖；活动引擎走 KieHelper 运行时编译。
 * **无写平面 bean**（ActivityMarketingService/ArtifactService/GenerationService 不在 classpath 上）→ 结构上不能写。
 *
 * <p>M2.2 起：本进程连**只读 DB 账号** + {@code ddl-auto=validate}（不碰 DDL），发布传播只靠 generation 轮询预热。
 * 主类放根包 {@code com.lrj.drools}（{@code @SpringBootTest} 沿测试包 {@code com.lrj.drools.activity} 向上找配置，须为祖先包）；
 * 显式声明扫描/实体/仓库基包 {@code com.lrj.drools}（此 classpath 下仅 activity.* 一支）。
 */
@SpringBootApplication(scanBasePackages = "com.lrj.drools")
@EntityScan("com.lrj.drools")
@EnableJpaRepositories("com.lrj.drools")
public class DecisionApplication {
    public static void main(String[] args) {
        SpringApplication.run(DecisionApplication.class, args);
    }
}
