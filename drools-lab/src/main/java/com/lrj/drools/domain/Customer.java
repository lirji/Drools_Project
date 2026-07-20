package com.lrj.drools.domain;

/**
 * Drools 把"事实"以普通 POJO 形式塞进 working memory。
 * 字段必须有 getter (规则里 `Customer( age >= 18 )` 实际调用 getAge())，
 * 用 Java 21 的 record 最省事。
 */
public record Customer(
        String name,
        int age,
        int vipLevel,             // 0 = 非会员, 1/2/3 = 普通/金/钻
        int yearsSinceRegistration
) {
}
