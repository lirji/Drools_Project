package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 发放分录台账仓储——只<b>追加</b>（{@code save}）与对账<b>读取</b>，不提供任何 update/delete
 * （台账不删不改，见 {@link ActivityGrantEntryEntity} 类注释）。
 *
 * <p>租户维度由 {@code @TenantId} 自动作用域，方法签名里不出现 tenantId；
 * 遵守 {@code TenantArchGuardTest} 禁 {@code nativeQuery} 的约束，全部走派生查询。
 */
public interface ActivityGrantEntryRepository extends JpaRepository<ActivityGrantEntryEntity, Long> {

    /**
     * 取某笔发放某类型的分录。confirm 幂等回读与 release 取 ISSUE 分额（供 REVERSAL 取负）都用它；
     * {@code uk_entry_grant_type(grant_no, entry_type)} 保证至多一条。
     */
    Optional<ActivityGrantEntryEntity> findFirstByGrantNoAndEntryType(String grantNo, String entryType);

    /** 某笔发放的全部分录（ISSUE + REVERSAL），按 id 升序——对账 / 客服「这笔发放怎么冲的」。 */
    List<ActivityGrantEntryEntity> findByGrantNoOrderByIdAsc(String grantNo);
}
