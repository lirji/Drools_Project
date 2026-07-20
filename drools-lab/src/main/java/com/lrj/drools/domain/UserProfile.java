package com.lrj.drools.domain;

/**
 * Step 18 引入: 活动资格判定的输入 fact —— 用户画像。
 *
 * 一次 /campaign/{id}/check 请求里, 这个 record 被 insert 进 working memory,
 * 活动绑定的资格规则 (LHS) 读它的字段判断够不够格。
 *
 * 字段尽量贴近真实营销平台常用的"资格维度":
 *   - registrationDays  注册天数 (新人活动看这个)
 *   - totalSpent        历史累计消费 (大客户活动看这个)
 *   - vipLevel          会员等级 (复用 Customer 的语义: 0 非会员 / 1 普通 / 2 金 / 3 钻)
 *   - city              地域 (区域活动看这个)
 *
 * 用 record 因为它是只读输入, 规则不会改它 (改的是衍生出来的 Eligibility 标记 fact)。
 */
public record UserProfile(
        String userId,
        int age,
        int vipLevel,
        int registrationDays,
        double totalSpent,
        String city
) {
}
