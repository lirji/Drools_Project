package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;

import java.math.BigDecimal;

/**
 * 商品池圈选规则。收敛自来源 {@code ActivityProductPoolRule}（去掉车辆维度字段，
 * 换成商品目录的类目/标签/价格）。一个 pool 对应一条启用规则。
 *
 * 圈选口径（对 {@code catalog_product}）：价格区间 [minPrice, maxPrice] + 类目 CSV + 标签 CSV，
 * 空字段=不限。当前平台用 JPA/内存过滤，后续可下推到目录查询服务。
 */
@Entity
@Comment("商品池圈选规则表")
@Table(name = "activity_product_pool_rule", indexes = {
        @Index(name = "idx_pr_pool_del", columnList = "tenant_id,pool_id,is_del")
})
public class ProductPoolRuleEntity extends SoftDeletableTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    public ProductPoolRuleEntity() {}

    public Long getId() { return id; }

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
}
