package com.lrj.drools.activity.service;

import com.lrj.drools.activity.persistence.ActivitySpuBindingEntity;
import com.lrj.drools.activity.persistence.ActivitySpuBindingRepository;
import com.lrj.drools.activity.persistence.DemoProductEntity;
import com.lrj.drools.activity.persistence.DemoProductRepository;
import com.lrj.drools.activity.persistence.PoolRefEntity;
import com.lrj.drools.activity.persistence.PoolRefRepository;
import com.lrj.drools.activity.persistence.ProductPoolRuleEntity;
import com.lrj.drools.activity.persistence.ProductPoolRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品池圈选 + 自动绑定物化。收敛自来源 {@code ActivityPoolMatchService} + {@code ActivityAutoBindRefreshService}。
 *
 * 来源用 SQL 打真实商品/车辆表；demo 用内存过滤 {@code demo_product}（数据集小）。
 * 成员资格 = 价格区间 + 类目 + 标签（稳定维度）；{@code effective} 随 {@code on_shelf} 翻转。
 * 绑定刷新按"目标态 diff"（幂等，可重复执行）。
 *
 * <p><b>为什么在 activity-console 而不是 activity-common</b>（R17）：本类有三处
 * {@code bindingRepo.save(...)}，是不折不扣的<b>写</b>路径，而 common 是 console 与 decision 共享的库——
 * 放在那里意味着这个 {@code @Service} 也会在只读的 decision 进程里被实例化。
 * 唯一调用方 {@code ActivityMarketingService} 本来就在 console，搬上来是零风险的物理归位。
 */
@Service
public class ActivityPoolMatchService {

    private static final int NOT_DEL = 0;
    private static final int DEL = 1;
    private static final int BIND_AUTO = 1;
    private static final int EFFECTIVE = 1;
    private static final int INEFFECTIVE = 0;

    private final ProductPoolRuleRepository poolRuleRepo;
    private final PoolRefRepository poolRefRepo;
    private final DemoProductRepository demoProductRepo;
    private final ActivitySpuBindingRepository bindingRepo;

    public ActivityPoolMatchService(ProductPoolRuleRepository poolRuleRepo,
                                    PoolRefRepository poolRefRepo,
                                    DemoProductRepository demoProductRepo,
                                    ActivitySpuBindingRepository bindingRepo) {
        this.poolRuleRepo = poolRuleRepo;
        this.poolRefRepo = poolRefRepo;
        this.demoProductRepo = demoProductRepo;
        this.bindingRepo = bindingRepo;
    }

    /** 按池规则圈选命中商品（返回全部成员，含下架，effective 由调用方按 on_shelf 定）。 */
    public List<DemoProductEntity> matchByRule(ProductPoolRuleEntity rule) {
        if (rule == null) return List.of();
        Set<String> categories = csvToSet(rule.getCategories());
        Set<String> tags = csvToSet(rule.getTags());
        return demoProductRepo.findAll().stream()
                .filter(p -> priceInRange(p.getPrice(), rule.getMinPrice(), rule.getMaxPrice()))
                .filter(p -> categories.isEmpty() || categories.contains(p.getCategory()))
                .filter(p -> tags.isEmpty() || overlaps(csvToSet(p.getTags()), tags))
                .collect(Collectors.toList());
    }

    /**
     * 把某活动版本引用的所有池的命中商品，物化进绑定表（bind_source=AUTO）。
     * 只增删 AUTO 行（手动行不动），按目标态 diff：新增 / 翻 effective / 逻辑删除。幂等。
     */
    @Transactional(rollbackFor = Exception.class)
    public int refreshActivityBinding(String activityId, Integer version) {
        List<PoolRefEntity> refs = poolRefRepo.findByActivityIdAndVersionAndIsDel(activityId, version, NOT_DEL);
        if (refs.isEmpty()) {
            // 没有池引用：把已有 AUTO 行全部逻辑删除
            return removeStaleAuto(activityId, version, Map.of());
        }

        // 目标态：spuId -> (storeId, effective, poolId)
        Map<Long, Target> target = new LinkedHashMap<>();
        for (PoolRefEntity ref : refs) {
            ProductPoolRuleEntity rule = poolRuleRepo
                    .findFirstByPoolIdAndEnabledAndIsDel(ref.getPoolId(), EFFECTIVE, NOT_DEL)
                    .orElse(null);
            if (rule == null) continue; // 池停用/无规则 → 该池不贡献成员
            for (DemoProductEntity p : matchByRule(rule)) {
                target.putIfAbsent(p.getSpuId(), new Target(
                        p.getStoreId(),
                        (p.getOnShelf() != null && p.getOnShelf() == 0) ? INEFFECTIVE : EFFECTIVE,
                        ref.getPoolId()));
            }
        }

        Instant now = Instant.now();
        List<ActivitySpuBindingEntity> current = bindingRepo
                .findByActivityIdAndVersionAndBindSourceAndIsDel(activityId, version, BIND_AUTO, NOT_DEL);
        Map<Long, ActivitySpuBindingEntity> currentBySpu = current.stream()
                .collect(Collectors.toMap(ActivitySpuBindingEntity::getSpuId, e -> e, (a, b) -> a));

        int changed = 0;
        // 新增 / 翻 effective
        for (Map.Entry<Long, Target> e : target.entrySet()) {
            ActivitySpuBindingEntity row = currentBySpu.get(e.getKey());
            if (row == null) {
                bindingRepo.save(newAutoBinding(activityId, version, e.getKey(), e.getValue(), now));
                changed++;
            } else if (!row.getEffective().equals(e.getValue().effective)) {
                row.setEffective(e.getValue().effective);
                row.setModifiedStime(now);
                bindingRepo.save(row);
                changed++;
            }
        }
        // 不再是成员 → 逻辑删除
        changed += removeStaleAuto(activityId, version, target);
        return changed;
    }

    private int removeStaleAuto(String activityId, Integer version, Map<Long, Target> target) {
        Instant now = Instant.now();
        List<ActivitySpuBindingEntity> current = bindingRepo
                .findByActivityIdAndVersionAndBindSourceAndIsDel(activityId, version, BIND_AUTO, NOT_DEL);
        int changed = 0;
        for (ActivitySpuBindingEntity row : current) {
            if (!target.containsKey(row.getSpuId())) {
                row.setIsDel(DEL);
                row.setModifiedStime(now);
                bindingRepo.save(row);
                changed++;
            }
        }
        return changed;
    }

    private ActivitySpuBindingEntity newAutoBinding(String activityId, Integer version, Long spuId, Target t, Instant now) {
        ActivitySpuBindingEntity row = new ActivitySpuBindingEntity();
        row.setActivityId(activityId);
        row.setVersion(version);
        row.setSpuId(spuId);
        row.setStoreId(t.storeId);
        row.setBindSource(BIND_AUTO);
        row.setPoolId(t.poolId);
        row.setEffective(t.effective);
        row.setIsDel(NOT_DEL);
        row.setCreatedStime(now);
        row.setModifiedStime(now);
        return row;
    }

    private boolean priceInRange(BigDecimal price, BigDecimal min, BigDecimal max) {
        if (price == null) return false;
        if (min != null && price.compareTo(min) < 0) return false;
        return max == null || price.compareTo(max) <= 0;
    }

    private boolean overlaps(Set<String> a, Set<String> b) {
        for (String s : a) if (b.contains(s)) return true;
        return false;
    }

    private Set<String> csvToSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private record Target(Integer storeId, Integer effective, Long poolId) {}
}
