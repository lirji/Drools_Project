package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 商品池。收敛自来源 {@code ActivityProductPool}。
 * {@code poolType}：1=规则圈选 2=手动维护；{@code status}：0 停用 1 启用。
 */
@Entity
@Table(name = "activity_product_pool")
public class ProductPoolEntity extends SoftDeletableTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pool_name", length = 128, nullable = false)
    private String poolName;

    @Column(name = "biz_line", length = 64)
    private String bizLine;

    @Column(name = "pool_type", nullable = false)
    private Integer poolType;

    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "remark", length = 512)
    private String remark;

    public ProductPoolEntity() {}

    public Long getId() { return id; }

    public String getPoolName() { return poolName; }
    public void setPoolName(String poolName) { this.poolName = poolName; }

    public String getBizLine() { return bizLine; }
    public void setBizLine(String bizLine) { this.bizLine = bizLine; }

    public Integer getPoolType() { return poolType; }
    public void setPoolType(Integer poolType) { this.poolType = poolType; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
