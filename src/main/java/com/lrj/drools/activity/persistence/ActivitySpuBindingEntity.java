package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/**
 * 商品绑定层。收敛自来源 {@code ActivityAdminStoreSpuProduct}。
 *
 * {@code bindSource}：0=手动 MANUAL，1=自动 AUTO（商品池圈选物化，随上下架翻 {@code effective}）。
 * 读取侧只取 {@code isDel=0 && effective=1}。
 */
@Entity
@Table(name = "activity_spu_binding", indexes = {
        @Index(name = "idx_sb_spu_eff_del", columnList = "tenant_id,spu_id,effective,is_del"),
        @Index(name = "idx_sb_aid_ver", columnList = "tenant_id,activity_id,version")
})
public class ActivitySpuBindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 租户隔离列（P0-4）：Hibernate @TenantId 自动为每条 SQL 追加 tenant_id 谓词、insert 自动落值，业务代码不手动 set。 */
    @TenantId
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "activity_id", length = 64, nullable = false)
    private String activityId;

    @Column(name = "store_id")
    private Integer storeId;

    @Column(name = "spu_id", nullable = false)
    private Long spuId;

    @Column(name = "version", nullable = false)
    private Integer version;

    /** 0=手动 1=自动(商品池)。 */
    @Column(name = "bind_source", nullable = false)
    private Integer bindSource;

    /** 自动绑定来源的商品池 id（bind_source=1 时有值）。 */
    @Column(name = "pool_id")
    private Long poolId;

    /** 1=生效 0=失效(商品下架)。 */
    @Column(name = "effective", nullable = false)
    private Integer effective;

    @Column(name = "is_del", nullable = false)
    private Integer isDel;

    @Column(name = "created_stime", nullable = false)
    private Instant createdStime;

    @Column(name = "modified_stime", nullable = false)
    private Instant modifiedStime;

    public ActivitySpuBindingEntity() {}

    public Long getId() { return id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getActivityId() { return activityId; }
    public void setActivityId(String activityId) { this.activityId = activityId; }

    public Integer getStoreId() { return storeId; }
    public void setStoreId(Integer storeId) { this.storeId = storeId; }

    public Long getSpuId() { return spuId; }
    public void setSpuId(Long spuId) { this.spuId = spuId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public Integer getBindSource() { return bindSource; }
    public void setBindSource(Integer bindSource) { this.bindSource = bindSource; }

    public Long getPoolId() { return poolId; }
    public void setPoolId(Long poolId) { this.poolId = poolId; }

    public Integer getEffective() { return effective; }
    public void setEffective(Integer effective) { this.effective = effective; }

    public Integer getIsDel() { return isDel; }
    public void setIsDel(Integer isDel) { this.isDel = isDel; }

    public Instant getCreatedStime() { return createdStime; }
    public void setCreatedStime(Instant createdStime) { this.createdStime = createdStime; }

    public Instant getModifiedStime() { return modifiedStime; }
    public void setModifiedStime(Instant modifiedStime) { this.modifiedStime = modifiedStime; }
}
