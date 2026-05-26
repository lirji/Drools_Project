package com.lrj.drools.config;

import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Drools 的 Spring 入口。
 *
 * 关键概念:
 * - KieServices: Drools 的"全局入口"，单例，从这里拿一切
 * - KieContainer: classpath 上 META-INF/kmodule.xml 描述的"规则容器"，包含所有 kbase
 * - KieSession: 运行时会话，从 container 按 ksession name 派生；每次请求建一个，用完 dispose()
 *
 * 这里把 KieContainer 注成 Bean，是因为它构建一次(扫描 + 编译所有 DRL)代价不低，
 * Session 是廉价的所以现用现建。
 */
@Configuration
public class DroolsConfig {

    @Bean
    public KieContainer kieContainer() {
        // 扫描 classpath 下的 META-INF/kmodule.xml + rules/**/*.drl，编译成内存中的规则库
        return KieServices.Factory.get().getKieClasspathContainer();
    }
}
