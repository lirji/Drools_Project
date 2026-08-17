package com.lrj.drools.domain;

/**
 * Step 19: LHS 量词补全 (collect / forall / eval) 的输出标记 fact。
 *
 * 跟 Step 4 的 Promotion / Step 8 的 BurstAlert 思路一致 —— 规则自己 insert 出来,
 * service 事后从 working memory 里捞出来当结果返回。用独立类型 (而不是复用 Promotion)
 * 是让"订单合规审查"这个 Step 的语义自解释, 不跟折扣推荐耦合。
 *
 * code 是机器可读的审查项编码 (BULK_BOOK / ALL_LINES_VALID / MANUAL_REVIEW),
 * detail 是给人看的说明。
 */
public record ReviewFinding(String code, String detail) {
}
