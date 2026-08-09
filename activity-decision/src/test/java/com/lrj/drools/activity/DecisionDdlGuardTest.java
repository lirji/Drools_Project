package com.lrj.drools.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构守卫：decision 是**只读平面**，配置里的 {@code ddl-auto} 必须是 {@code validate}。
 *
 * <p><b>为什么需要这条测试</b>：M2.2 定下「decision 连只读账号、DDL 由 console 独占」这条边界后，
 * 本模块 {@code application.yml} 里仍遗留 {@code ddl-auto: update}，注释写着要改 validate、值却没改。
 * 线上没出事只是因为 {@code deploy/docker-compose.yml} 用环境变量
 * {@code SPRING_JPA_HIBERNATE_DDL_AUTO=validate} 盖住了它——也就是说这条边界当时**只由部署编排保证，
 * 应用自身不保证**。按 CLAUDE.md 里文档化的本地命令
 * {@code ./mvnw -pl activity-decision spring-boot:run} 起，只读平面就会带着 DDL 权限跑。
 *
 * <p>本测试直接读源文件而不是读 Spring 环境：环境值会被 profile 与环境变量覆盖，
 * 那样测的是「本机这次怎么跑」，而这里要钉死的是「仓库里写的是什么」。
 */
@DisplayName("decision 只读平面：ddl-auto 必须是 validate")
class DecisionDdlGuardTest {

    /** 匹配 `ddl-auto: <value>`，允许行内注释。 */
    private static final Pattern DDL_AUTO = Pattern.compile("(?m)^\\s*ddl-auto:\\s*([A-Za-z-]+)");

    @Test
    void ddlAutoMustBeValidate() throws IOException {
        String yml = readClasspath("application.yml");

        Matcher m = DDL_AUTO.matcher(yml);
        assertTrue(m.find(), "activity-decision 的 application.yml 里应显式声明 ddl-auto（不要依赖默认值）");

        String value = m.group(1);
        assertEquals("validate", value,
                "decision 连只读账号、不得执行 DDL；建表由 console 独占。当前值=" + value
                        + "。若确需改动，请先改 deploy/docker-compose.yml 的只读账号约定并说明理由。");

        assertTrue(!m.find(), "application.yml 里出现了多处 ddl-auto，守卫无法判定生效值，请收敛成一处");
    }

    private static String readClasspath(String name) throws IOException {
        try (InputStream in = DecisionDdlGuardTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "classpath 上找不到 " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
