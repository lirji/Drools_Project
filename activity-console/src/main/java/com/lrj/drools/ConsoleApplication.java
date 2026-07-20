package com.lrj.drools;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * M2.1 · 控制台服务（console-svc，8081）主类。写平面 + Step 1–18 教学 + 唯一 DDL 执行者。
 *
 * <p>classpath = activity-common(共享域/引擎/持久化/租户 + 读服务) + drools-lab(Step1-18 教学 + 重 drools 依赖)
 * + 本模块写平面(ActivityMarketingService/ArtifactService/GenerationService/ActivityMarketingController/seeder)。
 * 故组件扫描覆盖三者：写面、读面(legacy /activity-marketing 读端点复用 ActivityQueryService)、Step1-18 全端点。
 *
 * <p>主类放在根包 {@code com.lrj.drools}（而非 {@code .console}）：测试在 {@code com.lrj.drools.activity} 包下，
 * {@code @SpringBootTest} 沿测试包向上找 {@code @SpringBootConfiguration}，须让主类是其祖先包。扫描/实体/仓库基包显式
 * 声明为 {@code com.lrj.drools}——覆盖 activity.* 与教学 com.lrj.drools.* 两支。decision 侧的 DecisionPlaneController/poller
 * 不在 console classpath 上，自然不被扫入。
 */
@SpringBootApplication(scanBasePackages = "com.lrj.drools")
@EntityScan("com.lrj.drools")
@EnableJpaRepositories("com.lrj.drools")
public class ConsoleApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConsoleApplication.class, args);
    }
}
