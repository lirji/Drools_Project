package com.lrj.drools.activity.persistence;

import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * 决策取数层用的**只读**合并策略仓库。继承 {@link Repository} 的理由见
 * {@link ActivityManageReadRepository} 类注释（R17）。
 *
 * <p>与其它几个只读仓库不同，这一条查询在 {@link ActivityStrategyRepository} 里<b>也保留着</b>——
 * 写平面创建活动时要读同一行做策略校验，那侧的调用方留在可写仓库上。
 * 两处签名必须一致（同一条派生查询，读侧改了写侧不改会变成两条不同的 SQL）。
 */
public interface ActivityStrategyReadRepository extends Repository<ActivityStrategyEntity, Long> {

    /** 业务线兜底（activityType 为 null）。 */
    Optional<ActivityStrategyEntity> findFirstByBizLineAndActivityTypeIsNullAndSceneAndIsDel(
            String bizLine, String scene, Integer isDel);
}
