package com.lrj.drools.activity.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 发放传播 outbox 仓储——写平面<b>追加</b>事件、中继<b>轮询可投递条目</b>并<b>CAS 置态</b>。
 *
 * <p>租户维度由 {@code @TenantId} 自动作用域（方法签名不出现 tenantId）；遵守
 * {@code TenantArchGuardTest} 禁 {@code nativeQuery} 的约束，全部走 JPQL / 派生查询。
 * 跨租户发现「哪些租户有待投递条目」不在此（会被 @TenantId 过滤成空），由
 * {@code GrantOutboxTenantScanner}（独立 JDBC 组件）负责，再逐租户回到本仓库的 @TenantId 隔离读。
 */
public interface ActivityGrantOutboxRepository extends JpaRepository<ActivityGrantOutboxEntity, Long> {

    /**
     * 幂等回读：某笔发放某事件是否已入队。{@code uk_outbox_grant_event(grant_no, event_type)} 保证至多一条。
     * 写点用它做「先查后插」的软兜底（唯一约束才是硬兜底），避免幂等重放触发唯一键异常污染外层事务。
     */
    Optional<ActivityGrantOutboxEntity> findFirstByGrantNoAndEventType(String grantNo, String eventType);

    /** 某笔发放发出过的全部事件（GRANT_ISSUED + GRANT_REVERSED），按 id 升序——客服 / 对账回溯。 */
    List<ActivityGrantOutboxEntity> findByGrantNoOrderByIdAsc(String grantNo);

    /**
     * 中继一轮可投递的条目（当前租户内）：{@code PENDING} 首投 + {@code FAILED} 且未触顶（{@code attempt < maxAttempt}）
     * 且已过退避（{@code nextAttemptAt <= now} 或为 null）的补投，按 id 升序、分页限量。{@code DEAD}（触顶死信）
     * 不在此、须经 {@link #redriveDead} 重置才回补投集。仿 recon {@code AlertOutboxRepository.listRetryable}。
     */
    @Query("select o from ActivityGrantOutboxEntity o "
         + "where o.status = 'PENDING' "
         + "   or (o.status = 'FAILED' and o.attempt < :maxAttempt "
         + "       and (o.nextAttemptAt is null or o.nextAttemptAt <= :now)) "
         + "order by o.id asc")
    List<ActivityGrantOutboxEntity> findRetryable(@Param("maxAttempt") int maxAttempt,
                                                  @Param("now") Instant now, Pageable pageable);

    /**
     * 置为已投递（{@code SENT}）——CAS {@code WHERE status <> 'SENT'}：并发两轮中继只有一轮生效，避免把
     * 已 SENT 的条目重复落 sentAt。@TenantId 自动追加租户谓词。
     *
     * @return 1 本轮首次置 SENT，0 已被并发中继置 SENT（幂等）
     */
    @Modifying(clearAutomatically = true)
    @Query("update ActivityGrantOutboxEntity o set o.status = 'SENT', o.sentAt = :now, o.modifiedStime = :now "
         + "where o.id = :id and o.status <> 'SENT'")
    int markSent(@Param("id") Long id, @Param("now") Instant now);

    /**
     * 置为失败（{@code FAILED}）并 {@code attempt + 1} + 退避到 {@code nextAttemptAt}——CAS {@code WHERE status <> 'SENT'}：
     * 绝不把已成功投递的条目改回失败（防迟到的失败回执覆盖成功态）。中继下轮只在 {@code nextAttemptAt <= now} 时补投。
     *
     * @return 1 置 FAILED 成功，0 该条已 SENT（不回退）
     */
    @Modifying(clearAutomatically = true)
    @Query("update ActivityGrantOutboxEntity o set o.status = 'FAILED', o.attempt = o.attempt + 1, "
         + "o.nextAttemptAt = :nextAttemptAt, o.modifiedStime = :now where o.id = :id and o.status <> 'SENT'")
    int markFailed(@Param("id") Long id, @Param("now") Instant now, @Param("nextAttemptAt") Instant nextAttemptAt);

    /**
     * 置为死信（{@code DEAD}）并 {@code attempt + 1}——达 maxAttempt 后退出自动补投，等 {@link #redriveDead}。
     * CAS {@code WHERE status <> 'SENT'}（防覆盖成功态）。资金类事件绝不静默丢弃，只是转人工可控（可 redrive 复活）。
     *
     * @return 1 置 DEAD 成功，0 该条已 SENT
     */
    @Modifying(clearAutomatically = true)
    @Query("update ActivityGrantOutboxEntity o set o.status = 'DEAD', o.attempt = o.attempt + 1, "
         + "o.modifiedStime = :now where o.id = :id and o.status <> 'SENT'")
    int markDead(@Param("id") Long id, @Param("now") Instant now);

    /**
     * 重投死信：把 {@code DEAD} 重置回 {@code PENDING}（attempt 归 0、清退避），供人工/管理端在下游恢复后一键补投。
     * {@code @TenantId} 自动作用域于当前租户。<b>这是资金事件「进死信可复活」的入口——修掉「触顶即永久丢失」（KI-9）。</b>
     *
     * @return 重置的条数
     */
    @Modifying(clearAutomatically = true)
    @Query("update ActivityGrantOutboxEntity o set o.status = 'PENDING', o.attempt = 0, "
         + "o.nextAttemptAt = null, o.modifiedStime = :now where o.status = 'DEAD'")
    int redriveDead(@Param("now") Instant now);
}
