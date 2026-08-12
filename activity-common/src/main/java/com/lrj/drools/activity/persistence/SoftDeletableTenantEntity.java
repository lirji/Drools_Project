package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

/**
 * 第二层：在 {@link TenantScopedEntity} 之上加<b>软删标记</b>。
 *
 * <p>配置类实体（活动 / 规则 / 条件 / 绑定 / 赠品 / 池 / 策略）一律软删而不物删——
 * 版本化编辑靠它把旧版本行标掉，历史配置必须能回查「那天到底按哪一版发的」。
 * 与之相对，{@code activity_grant} 是账不是配置，它没有这一列，因此只继承第一层。
 *
 * <p>{@code isDel} 仍由各处 {@code save*} 显式 {@code setIsDel(NOT_DEL)} 落值：
 * 它是 {@code nullable=false}，漏填在 flush 时<b>响亮失败</b>，数据库已经在做这件事，
 * 不需要再造一层「让漏填不可表达」的装配器把同一条强制搬得更远。
 */
@MappedSuperclass
public abstract class SoftDeletableTenantEntity extends TenantScopedEntity {

    @Column(name = "is_del", nullable = false)
    private Integer isDel;

    public Integer getIsDel() { return isDel; }
    public void setIsDel(Integer isDel) { this.isDel = isDel; }
}
