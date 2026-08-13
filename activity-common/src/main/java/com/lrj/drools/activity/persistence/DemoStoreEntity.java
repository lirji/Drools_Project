package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

/**
 * demo 店铺表 —— 供「选店铺→勾商品」picker 展示店铺名。
 *
 * <p>来源用真实门店表；这里只保留 picker 需要的最小字段。与 {@link DemoProductEntity} 一样是**最小替身表**：
 * 主键用业务自带的 {@code store_id}（与 {@code demo_product.store_id} / 绑定层 {@code store_id} 对齐），
 * 内联 {@code @TenantId} 自动隔离，**不**继承 {@link TenantScopedEntity}（替身表不需软删 / 双时间戳 / 键序副作用）。
 *
 * <p>与 {@code demo_product.store_id} 是<b>逻辑引用，不加物理外键</b>：与「join 不到就回退『店铺 #id』」的
 * 容错范式一致（见 {@code ActivitySpuBindingRepository} 注释、前端 {@code BindingStores} 店名回退）。
 * 店铺列表由 {@code demo_product} 的 {@code group by store_id} 驱动，本表只负责补店名。
 */
@Entity
@Table(name = "demo_store", indexes = {
        @Index(name = "idx_ds_on_shelf", columnList = "tenant_id,on_shelf")
})
public class DemoStoreEntity {

    /** 业务键，与 demo_product.store_id / 绑定层对齐。 */
    @Id
    @Column(name = "store_id", nullable = false)
    private Integer storeId;

    /** 租户隔离列：Hibernate @TenantId 自动为每条 SQL 追加 tenant_id 谓词、insert 自动落值，业务代码不手动 set。 */
    @TenantId
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "store_name", length = 128)
    private String storeName;

    /** 1=营业 0=整店下架。 */
    @Column(name = "on_shelf", nullable = false)
    private Integer onShelf;

    public DemoStoreEntity() {}

    public DemoStoreEntity(Integer storeId, String storeName, Integer onShelf) {
        this.storeId = storeId;
        this.storeName = storeName;
        this.onShelf = onShelf;
    }

    public Integer getStoreId() { return storeId; }
    public void setStoreId(Integer storeId) { this.storeId = storeId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }

    public Integer getOnShelf() { return onShelf; }
    public void setOnShelf(Integer onShelf) { this.onShelf = onShelf; }
}
