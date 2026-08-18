package com.lrj.drools.guard;

import org.kie.api.definition.rule.Rule;
import org.kie.api.runtime.rule.AgendaFilter;
import org.kie.api.runtime.rule.Match;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Step 14: 按规则 `@release(...)` 元数据做"发布通道"放行的 AgendaFilter。
 *
 * AgendaFilter 是 Drools 的运行时护栏: fireAllRules(filter) 在每条 activation
 * **真正 fire 之前**调一次 accept(match), 返回 false 这条就被跳过 (不执行 RHS,
 * 但仍留在 agenda 上, 下次没 filter 的 fire 还能跑)。
 *
 * 用它做灰度/金丝雀/紧急下线: 给规则标 @release("canary"), 线上只放行
 * allowed={"stable"} → canary 规则编译进了 KieBase 但运行时不生效;
 * 想放量就把 "canary" 加进白名单, **不重编译 KieBase、不重启**。
 *
 * 关键: 读元数据走的是公共 API `Rule.getMetaData()` (Map<String,Object>),
 * 不像 getAgendaGroup() 那样只在 internal RuleImpl 上 (见 RuleAuditListener 注释)。
 *
 * 约定: 没标 @release 的规则视为"稳定基线", 默认放行 — 灰度只控带标记的实验规则,
 * 不会因为忘了标 release 就把基线规则一起拦掉。
 *
 * 跟 KieSession 一样按请求新建 (skipped 是非线程安全 ArrayList)。
 */
public class ReleaseAgendaFilter implements AgendaFilter {

    /** 元数据 key, 对应 DRL 里的 `@release("xxx")`。 */
    public static final String RELEASE_KEY = "release";

    private final Set<String> allowedReleases;
    private final List<String> skipped = new ArrayList<>();

    public ReleaseAgendaFilter(Set<String> allowedReleases) {
        this.allowedReleases = allowedReleases;
    }

    @Override
    public boolean accept(Match match) {
        Rule rule = match.getRule();
        Object release = rule.getMetaData().get(RELEASE_KEY);

        if (release == null) {
            return true;  // 没标 release → 稳定基线, 永远放行
        }
        boolean allowed = allowedReleases.contains(release.toString());
        if (!allowed) {
            skipped.add(rule.getName() + " (release=" + release + ")");
        }
        return allowed;
    }

    /** 本次 fire 被拦下的规则（规则名 + release 通道），用于在能力结果中展示灰度效果。 */
    public List<String> skipped() {
        return Collections.unmodifiableList(skipped);
    }
}
