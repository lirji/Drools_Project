package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;

/**
 * demo 商品表 —— 本项目自带的替身商品库，供商品池圈选有数据可选。
 *
 * 来源用真实商品/车辆表；这里只保留圈选需要的最小字段。{@code onShelf} 用来演示
 * "商品上下架 → 自动绑定 effective 翻转"（1=在架 0=下架）。
 *
 * 主键用业务自带的 spuId（与绑定层对齐）。
 */
@Entity
@Table(name = "demo_product", indexes = {
        @Index(name = "idx_dp_price", columnList = "tenant_id,price"),
        @Index(name = "idx_dp_category", columnList = "tenant_id,category"),
        // 「选店铺→勾商品」picker 按 store_id 列商品（group by / 分页）走全索引命中
        @Index(name = "idx_dp_store", columnList = "tenant_id,store_id")
})
public class DemoProductEntity {

    @Id
    @Column(name = "spu_id", nullable = false)
    private Long spuId;

    /** 租户隔离列（P0-4）：Hibernate @TenantId 自动为每条 SQL 追加 tenant_id 谓词、insert 自动落值，业务代码不手动 set。 */
    @TenantId
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "store_id")
    private Integer storeId;

    @Column(name = "spu_name", length = 128)
    private String spuName;

    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "price", precision = 12, scale = 2)
    private BigDecimal price;

    /** 标签 CSV（如 "hot,newuser"）。 */
    @Column(name = "tags", length = 512)
    private String tags;

    /** 1=在架 0=下架。 */
    @Column(name = "on_shelf", nullable = false)
    private Integer onShelf;

    public DemoProductEntity() {}

    public DemoProductEntity(Long spuId, Integer storeId, String spuName, String category,
                             BigDecimal price, String tags, Integer onShelf) {
        this.spuId = spuId;
        this.storeId = storeId;
        this.spuName = spuName;
        this.category = category;
        this.price = price;
        this.tags = tags;
        this.onShelf = onShelf;
    }

    public Long getSpuId() { return spuId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public void setSpuId(Long spuId) { this.spuId = spuId; }

    public Integer getStoreId() { return storeId; }
    public void setStoreId(Integer storeId) { this.storeId = storeId; }

    public String getSpuName() { return spuName; }
    public void setSpuName(String spuName) { this.spuName = spuName; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public Integer getOnShelf() { return onShelf; }
    public void setOnShelf(Integer onShelf) { this.onShelf = onShelf; }
}
