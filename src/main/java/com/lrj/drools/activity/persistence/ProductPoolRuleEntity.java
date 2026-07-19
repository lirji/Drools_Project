package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 商品池圈选规则。收敛自来源 {@code ActivityProductPoolRule}（去掉车辆维度字段，
 * 换成 demo 商品的类目/标签/价格）。一个 pool 对应一条启用规则。
 *
 * 圈选口径（对 {@code demo_product}）：价格区间 [minPrice, maxPrice] + 类目 CSV + 标签 CSV，
 * 空字段=不限。来源用 SQL；demo 用 JPA/内存过滤（阶段 2）。
 */
@Entity
@Table(name = "activity_product_pool_rule", indexes = {
        @Index(name = "idx_pr_pool_del", columnList = "tenant_id,pool_id,is_del")
})
public class ProductPoolRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 租户隔离列（P0-4）：Hibernate @TenantId 自动为每条 SQL 追加 tenant_id 谓词、insert 自动落值，业务代码不手动 set。 */
    @TenantId
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    @Column(name = "min_price", precision = 12, scale = 2)
    private BigDecimal minPrice;

    @Column(name = "max_price", precision = 12, scale = 2)
    private BigDecimal maxPrice;

    /** 类目 CSV，空=不限。 */
    @Column(name = "categories", length = 512)
    private String categories;

    /** 标签 CSV，空=不限（命中任一即算）。 */
    @Column(name = "tags", length = 512)
    private String tags;

    @Column(name = "enabled", nullable = false)
    private Integer enabled;

    @Column(name = "is_del", nullable = false)
    private Integer isDel;

    @Column(name = "created_stime", nullable = false)
    private Instant createdStime;

    @Column(name = "modified_stime", nullable = false)
    private Instant modifiedStime;

    public ProductPoolRuleEntity() {}

    public Long getId() { return id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getPoolId() { return poolId; }
    public void setPoolId(Long poolId) { this.poolId = poolId; }

    public BigDecimal getMinPrice() { return minPrice; }
    public void setMinPrice(BigDecimal minPrice) { this.minPrice = minPrice; }

    public BigDecimal getMaxPrice() { return maxPrice; }
    public void setMaxPrice(BigDecimal maxPrice) { this.maxPrice = maxPrice; }

    public String getCategories() { return categories; }
    public void setCategories(String categories) { this.categories = categories; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public Integer getIsDel() { return isDel; }
    public void setIsDel(Integer isDel) { this.isDel = isDel; }

    public Instant getCreatedStime() { return createdStime; }
    public void setCreatedStime(Instant createdStime) { this.createdStime = createdStime; }

    public Instant getModifiedStime() { return modifiedStime; }
    public void setModifiedStime(Instant modifiedStime) { this.modifiedStime = modifiedStime; }
}
