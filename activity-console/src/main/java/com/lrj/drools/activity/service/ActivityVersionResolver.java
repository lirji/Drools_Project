package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.ActivityStatus;
import com.lrj.drools.activity.persistence.ActivityManageEntity;
import com.lrj.drools.activity.persistence.ActivityManageRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * 「当前是哪一版」的<b>唯一出口</b>。
 *
 * <p>这个问题在写平面有<b>两套互斥定义</b>，从前它们各自散在五个调用点里就地解释一次，
 * 于是「秒杀库存的闸门装在草稿上」这类事故没有任何一处能被读出来：
 * <ul>
 *   <li>{@link #latestDraftVersion 最高未删除版}——编辑基线 / 详情 / {@code changeStatus} 的缺省</li>
 *   <li>{@link #currentOnlineVersion 最高 ONLINE 版}——正在发钱的那一版：{@code claimInventory} 的缺省，
 *       也是决策侧 {@code DecisionDataLoader} 认的那一版</li>
 * </ul>
 *
 * <p>它独立成 bean 而不是挂在某个 service 上，是因为两个消费者
 * （{@link ActivityMarketingService} 与 {@link GrantService}）之间不该产生依赖——
 * 发放台账与配置写入口零共享状态，唯一的公共知识就是这两条版本定义。
 */
@Service
public class ActivityVersionResolver {

    private static final int NOT_DEL = 0;

    private final ActivityManageRepository manageRepo;

    public ActivityVersionResolver(ActivityManageRepository manageRepo) {
        this.manageRepo = manageRepo;
    }

    /**
     * <b>最高未删除版</b>——编辑基线 / {@code changeStatus} 的缺省 / {@code getDetail}。
     * P0-4「编辑不下线线上版」之后，线上 v1 与草稿 v2 是并存的，这个出口给的通常是<b>草稿</b>；
     * 活动只有一版且已上线时它与 {@link #currentOnlineVersion} 恰好相等，但那是巧合不是同义词。
     *
     * @return 版本号；活动不存在（或全部版本已软删）时返回 null
     */
    public Integer latestDraftVersion(String activityId) {
        return latestVersionRow(activityId).map(ActivityManageEntity::getVersion).orElse(null);
    }

    /**
     * <b>最高 ONLINE 版</b>——正在服务（正在发钱）的那一版：{@link GrantService#claimInventory} 的缺省，
     * 也是决策侧 {@code DecisionDataLoader} 认的那一版。
     *
     * <p>与 {@link #latestDraftVersion} 是**两套互斥定义**，调用点必须显式选一个：
     * 把秒杀库存的闸门装在草稿上，等于线上版本的库存一件没少。
     *
     * @return 版本号；没有上线版本时返回 null
     */
    public Integer currentOnlineVersion(String activityId) {
        return manageRepo.findByActivityIdAndActivityStatusAndIsDel(
                        activityId, ActivityStatus.ONLINE.code(), NOT_DEL).stream()
                .map(ActivityManageEntity::getVersion)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
    }

    /** {@link #latestDraftVersion} 的整行版本（编辑与详情要的是整行，不只是版本号）。 */
    public Optional<ActivityManageEntity> latestVersionRow(String activityId) {
        return manageRepo.findFirstByActivityIdAndIsDelOrderByVersionDesc(activityId, NOT_DEL);
    }
}
