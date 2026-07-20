package com.lrj.drools.domain;

import org.kie.api.definition.type.Position;

/**
 * Step 13: 后向链经典教学 fact —— "thing 在 container 里"。
 *
 * 一组 Location facts 描绘了一棵嵌套层级树:
 *   new Location("Office",   "House")
 *   new Location("House",    "City")
 *   new Location("City",     "Country")
 *   new Location("Country",  "Continent")
 *
 * 用 query isContainedIn(x, y) + 递归, 就能问出 "Office 是否最终在 Country 里"
 * 这种间接关系 — 而 working memory 里并没有 Location("Office", "Country") 这条
 * 直接事实。这就是后向链区别于前向链的核心: 不是数据驱动衍生结论, 而是查询驱动
 * 反向递归推理"为了证明这个结论需要哪些前提"。
 *
 * ─────────── 为什么需要 @Position ───────────
 * DRL 里 `Location(x, y;)` 是**位置模式 (positional pattern)**, 按字段下标解构。
 * Drools 不会"按声明顺序"猜 — 必须显式用 @Position(N) 告诉引擎哪个字段是第 N 位。
 * 不加注解会报: "Unable to find @Positional field 0 for class Location"。
 *
 * record 的组件注解默认只走到 RECORD_COMPONENT, @Position 的 target 包含 FIELD,
 * 编译器会把它一并下放到对应的私有 final 字段, Drools 能识别。
 */
public record Location(
        @Position(0) String thing,
        @Position(1) String container
) {
}
