package com.lrj.drools.config;

import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.io.Resource;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;

/**
 * Drools 的 Spring 入口。
 *
 * 早期写法 `KieServices.get().getKieClasspathContainer()` 能自动扫 classpath 上的
 * kmodule.xml + .drl, 但 Drools 8.44.2 的 ClasspathKieProject **不识别 spreadsheet
 * 决策表** (.xls/.xlsx/.csv) — 即使文件就在 kbase 的 packages 目录下也会被忽略,
 * 启动日志报 "No files found for KieBase decisionKBase"。
 *
 * 所以这里改成程序化构建:
 *   1. 写入原 kmodule.xml (kbase / ksession 拓扑不变)
 *   2. 扫所有 rules/** 下的 .drl, 按相对路径写入 KieFileSystem
 *   3. 额外把决策表显式标 ResourceType.DTABLE 加进去
 *   4. 用 KieBuilder 编译, 拿到 KieContainer
 *
 * Trade-off: 比一行的 getKieClasspathContainer() 啰嗦, 但换来对决策表/未来 .drt
 * 模板等"非 DRL 资源"的精确控制。
 */
@Configuration
public class DroolsConfig {

    @Bean
    public KieContainer kieContainer() throws IOException {
        KieServices ks = KieServices.get();
        KieFileSystem kfs = ks.newKieFileSystem();

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        // 1. kmodule.xml (kbase / ksession 声明)
        var kmodule = resolver.getResource("classpath:META-INF/kmodule.xml");
        kfs.writeKModuleXML(kmodule.getContentAsByteArray());

        // 2. 所有 .drl 文件
        for (var r : resolver.getResources("classpath*:rules/**/*.drl")) {
            String path = "src/main/resources/rules/" + extractRulesRelative(r.getURL().toString());
            kfs.write(path, ks.getResources().newByteArrayResource(r.getContentAsByteArray()));
        }

        // 3. 决策表 (必须显式标 ResourceType.DTABLE)
        for (var r : resolver.getResources("classpath*:rules/**/*.xls")) {
            String path = "src/main/resources/rules/" + extractRulesRelative(r.getURL().toString());
            Resource res = ks.getResources()
                    .newByteArrayResource(r.getContentAsByteArray())
                    .setResourceType(ResourceType.DTABLE)
                    .setSourcePath(path);
            kfs.write(res);
        }

        KieBuilder kb = ks.newKieBuilder(kfs).buildAll();
        if (kb.getResults().hasMessages(Message.Level.ERROR)) {
            throw new IllegalStateException("Drools build errors: " + kb.getResults().getMessages());
        }
        return ks.newKieContainer(kb.getKieModule().getReleaseId());
    }

    private static String extractRulesRelative(String url) {
        // url 形如 ".../target/classes/rules/decision/vip-discount.xls" 或
        // ".../target/test-classes/rules/...", 截掉前缀只留 kbase 包路径下的部分
        int idx = url.indexOf("/rules/");
        return url.substring(idx + "/rules/".length());
    }
}
