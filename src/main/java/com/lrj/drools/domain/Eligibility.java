package com.lrj.drools.domain;

/**
 * Step 18 引入: 资格判定的"标记 fact" —— 跟 Step 4 的 {@link Promotion} 同款套路。
 *
 * 白名单式判定 (本 Step 选定的风格):
 *   - working memory 里默认没有 Eligibility
 *   - 活动规则只在用户**满足**条件时 `insert(new Eligibility(true, "理由"))`
 *   - fire 完 service 检查有没有 Eligibility(eligible == true): 有 → 够格, 无 → 不够格
 *
 * 这样"默认不够格、命中规则才放行"的语义最贴合"满足规则才能参加活动"的原始诉求,
 * 也最安全 —— 漏写规则的后果是"没人够格", 而不是"所有人都放进来"。
 *
 * reason 让前端能告诉用户"因为满足 XX 条件所以够格", 一个活动可能有多条规则命中,
 * 所以 service 会把所有 Eligibility 的 reason 收集成列表返回。
 */
public record Eligibility(boolean eligible, String reason) {
}
