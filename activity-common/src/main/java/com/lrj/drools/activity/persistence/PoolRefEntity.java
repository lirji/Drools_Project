package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 活动↔商品池关联。收敛自来源 {@code ActivityPoolRef}。
 * 一个活动可引用多个池；一个池可被多个活动复用。链接键 = (activityId, version, poolId)。
 */
@Entity
@Table(name = "activity_pool_ref", indexes = {
        @Index(name = "idx_ref_aid_ver_del", columnList = "tenant_id,activity_id,version,is_del"),
        @Index(name = "idx_ref_pool_del", columnList = "tenant_id,pool_id,is_del")
})
public class PoolRefEntity extends SoftDeletableTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_id", length = 64, nullable = false)
    private String activityId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    public PoolRefEntity() {}

    public Long getId() { return id; }

    public String getActivityId() { return activityId; }
    public void setActivityId(String activityId) { this.activityId = activityId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public Long getPoolId() { return poolId; }
    public void setPoolId(Long poolId) { this.poolId = poolId; }
}
