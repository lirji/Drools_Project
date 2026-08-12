package com.lrj.drools.activity.persistence;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * 活动域实体的<b>第一层公共列</b>：租户 + 双时间戳。
 *
 * <p>这三列此前在每个实体里各写一遍（连同那句一模一样的 {@code @TenantId} 注释），
 * 全仓 66 处 {@code setIsDel/setCreatedStime/setModifiedStime} 而 {@code @MappedSuperclass} 零命中。
 * 重复本身不贵，贵的是<b>它们必须一致</b>：{@code tenant_id} 少一处 {@code @TenantId}
 * 就是一张不过滤的表（{@code TenantArchGuardTest} 守的正是这条），
 * {@code length=64} 各写各的就会漂移成两种列宽。
 *
 * <p><b>分两层的原因</b>：{@code activity_grant}（发放流水）确实<b>没有</b> {@code is_del} 列——
 * 发放不软删，冲正走 {@code state=RELEASED}（那是账，删掉就对不上）。
 * 所以带软删的那层单独在 {@link SoftDeletableTenantEntity}，不为了「都一样」给台账硬塞一列。
 *
 * <p><b>列定义逐字节照搬原实体</b>：列名、长度、{@code nullable} 全部不变，
 * 生成的 DDL 与改造前完全一致（console 是唯一 DDL 执行者，decision 侧是 {@code ddl-auto: validate}，
 * 这里任何一处漂移都会让只读平面起不来）。
 *
 * <p>不含 {@code @Id}：各表主键并不同形（{@code demo_product} 用业务键 {@code spu_id}，
 * 其余是自增代理键），把它收上来只会逼出一堆例外。
 */
// 序列化时把身份字段提回队首。**这不是洁癖，是在修一个副作用**：
// Jackson 默认把超类属性排在子类之前，所以把 tenantId / 双时间戳 / isDel 收进
// @MappedSuperclass 的那一刻，/activity-marketing/{list,detail,grants} 里每个实体对象
// 的键序就从 {"id":…,"activityId":…} 变成了 {"tenantId":…,"createdStime":…,…}——
// 字段名与取值一个字节没变，但响应体确实改了，而且没有任何信号。
// 前端按键取值不受影响，可对响应做 hash / ETag / 快照比对的下游会静默飘。
//
// 这里列的名字是**子类**的属性，超类自己并没有它们；Jackson 对列表里不存在的属性名
// 直接忽略，所以这一个注解能同时服务十个子类（有 activityId 的按 id→activityId→version 排，
// 没有的自然跳过），不必逐个实体贴。顺序由 EntityJsonOrderTest 钉住。
@JsonPropertyOrder({"id", "activityId", "version"})
@MappedSuperclass
public abstract class TenantScopedEntity {

    /** 租户隔离列（P0-4）：Hibernate @TenantId 自动为每条 SQL 追加 tenant_id 谓词、insert 自动落值，业务代码不手动 set。 */
    @TenantId
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "created_stime", nullable = false)
    private Instant createdStime;

    @Column(name = "modified_stime", nullable = false)
    private Instant modifiedStime;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getCreatedStime() { return createdStime; }
    public void setCreatedStime(Instant createdStime) { this.createdStime = createdStime; }

    public Instant getModifiedStime() { return modifiedStime; }
    public void setModifiedStime(Instant modifiedStime) { this.modifiedStime = modifiedStime; }
}
