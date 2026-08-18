package com.lrj.drools.service;

import com.lrj.drools.domain.Eligibility;
import com.lrj.drools.domain.UserProfile;
import com.lrj.drools.persistence.CampaignEntity;
import com.lrj.drools.persistence.CampaignRepository;
import org.kie.api.KieBase;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Step 18: 营销活动资格判定。
 *
 * 一个把前面几项能力组合起来的实际业务场景：
 *   - 创建活动时**绑定一段资格规则 (DRL)** —— 复用 Step 9 的 KieHelper 运行时编译
 *   - 规则文本持久化到 H2 (campaign 表) —— 复用 Step 10 的 JPA + H2 思路
 *   - 用户申请参加 → 插 UserProfile → fire → 看有没有产出 Eligibility 标记 fact
 *     —— 复用 Step 4 的"insert 标记 fact"白名单套路
 *
 * 两级存储 (内存 KieBase 缓存 + DB 规则文本) 的分工:
 *   - registry (ConcurrentHashMap): campaignId → 已编译的 KieBase, 热路径直接拿来跑
 *   - campaign 表: 规则源文本, 应用重启后内存缓存空了, check 时按需从 DB 捞 DRL 重新编译
 *     (rehydrate)。这就是"Step 9 + 持久化"比纯 Step 9 多出来的能力: 活动不随重启丢失。
 *
 * 多活动天然隔离: 一个 campaignId 一个独立 KieBase, 各跑各的 working memory,
 * 不会像 Step 12 那样担心衍生 fact 互相污染, 也不需要 agenda-group 划分。
 */
@Service
public class CampaignService {

    private final CampaignRepository repository;

    // campaignId → 已编译 KieBase。Service 单例, create / check 可能并发, 用 ConcurrentHashMap。
    private final Map<String, KieBase> registry = new ConcurrentHashMap<>();

    public CampaignService(CampaignRepository repository) {
        this.repository = repository;
    }

    /**
     * 创建 (或同 id 覆盖更新) 一个活动, 绑定资格规则。
     *
     * 先编译 DRL 校验语法 —— 编译失败抛 IllegalArgumentException (带行号),
     * 绝不把跑不起来的规则存进库。编译通过才落库 + 进内存缓存。
     */
    public CampaignEntity create(String campaignId, String name, String drl) {
        KieBase compiled = compile(drl); // 失败直接抛, 不落库

        Instant now = Instant.now();
        CampaignEntity entity = repository.findById(campaignId)
                .map(e -> {
                    e.setName(name);
                    e.setEligibilityDrl(drl);
                    e.setStatus("ACTIVE");
                    e.setUpdatedAt(now);
                    return e;
                })
                .orElseGet(() -> new CampaignEntity(campaignId, name, drl, "ACTIVE", now, now));
        repository.save(entity);

        registry.put(campaignId, compiled);
        return entity;
    }

    /**
     * 判定某用户是否够格参加某活动 (白名单式)。
     *
     * 流程: 拿到活动 KieBase (内存没有就从 DB 捞 DRL 重新编译) → newKieSession
     *       → insert(UserProfile) → fireAllRules → 收集 Eligibility 标记 fact → dispose。
     * 只要有一个 Eligibility(eligible == true) 就算够格; 一条没有就是不够格。
     */
    public CheckResult check(String campaignId, UserProfile user) {
        CampaignEntity entity = repository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("活动不存在: " + campaignId));
        if ("ENDED".equals(entity.getStatus())) {
            throw new IllegalStateException("活动已结束: " + campaignId);
        }

        KieBase base = registry.computeIfAbsent(campaignId, id -> compile(entity.getEligibilityDrl()));

        KieSession session = base.newKieSession();
        try {
            session.insert(user);
            int fired = session.fireAllRules();

            List<String> reasons = session.getObjects(o -> o instanceof Eligibility).stream()
                    .map(o -> (Eligibility) o)
                    .filter(Eligibility::eligible)
                    .map(Eligibility::reason)
                    .collect(Collectors.toList());

            boolean eligible = !reasons.isEmpty();
            return new CheckResult(campaignId, user.userId(), eligible, reasons, fired);
        } finally {
            session.dispose();
        }
    }

    /** 结束活动 (软状态)。规则文本保留在库里, 但 check 会被拒, 内存缓存清掉省内存。 */
    public void end(String campaignId) {
        CampaignEntity entity = repository.findById(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("活动不存在: " + campaignId));
        entity.setStatus("ENDED");
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
        registry.remove(campaignId);
    }

    public List<CampaignSummary> list() {
        return repository.findAll().stream()
                .map(e -> new CampaignSummary(e.getCampaignId(), e.getName(), e.getStatus(),
                        registry.containsKey(e.getCampaignId())))
                .collect(Collectors.toList());
    }

    public Optional<CampaignEntity> get(String campaignId) {
        return repository.findById(campaignId);
    }

    /**
     * DRL 字符串 → KieBase, 编译失败抛带行号的 IllegalArgumentException。
     * 跟 Step 9 HotReloadService.upsert 里那段是同一套 KieHelper 校验流程。
     */
    private KieBase compile(String drl) {
        KieHelper helper = new KieHelper();
        helper.addContent(drl, ResourceType.DRL);

        Results results = helper.verify();
        if (results.hasMessages(Message.Level.ERROR)) {
            String detail = results.getMessages(Message.Level.ERROR).stream()
                    .map(m -> "line " + m.getLine() + ": " + m.getText())
                    .collect(Collectors.joining("\n"));
            throw new IllegalArgumentException("资格规则编译失败:\n" + detail);
        }
        return helper.build();
    }

    public record CheckResult(String campaignId, String userId, boolean eligible,
                              List<String> reasons, int firedCount) {}

    /** cached = 该活动的 KieBase 当前是否在内存缓存里 (重启后第一次 check 前为 false)。 */
    public record CampaignSummary(String campaignId, String name, String status, boolean cached) {}
}
