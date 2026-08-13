package com.lrj.drools.activity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 中国行政区划（省 / 地市 / 区县 三级）编码字典表。
 *
 * <p><b>它补的是哪个洞</b>：活动侧一直在用 6 位行政区划代码——写平面的
 * {@code activity_manage.district_ids}（活动投放地域，CSV）与决策入参
 * {@code userDistrictId}（用户地域，资格条件字段之一，见 {@code RuleSchemaRegistry}）——
 * 但仓库里<b>没有任何一处能把 {@code 440305} 翻译成「广东省/深圳市/南山区」</b>。
 * 于是运营在控制台配地域只能手敲数字、配错了也没人拦，客服拿到一串代码也查不出是哪。
 * 本表就是这批代码的**唯一出处**。
 *
 * <p><b>为什么没有 {@code @TenantId}（与其余活动实体相反）</b>：行政区划是国家标准，
 * 不是任何一个租户的业务数据。加了租户列就意味着每来一个租户复制一份 3212 行的字典，
 * 而且判别式多租户下 <b>seeder 在启动期没有请求上下文</b>，写进去的那份只有某一个兜底租户看得见。
 * 已在 {@code TenantArchGuardTest.GLOBAL_ENTITIES} 显式登记豁免——那份白名单存在的意义
 * 正是「全局表要写下来、而不是悄悄漏掉租户列」。
 *
 * <p><b>层级与冗余列</b>：{@code districtLevel} 1=省级 2=地市级 3=区县级；
 * {@code parentCode} 是上级代码（省级为 {@code null}）。
 * {@code provinceCode} / {@code cityCode} 是<b>含自身</b>的祖先冗余列——
 * 有了它们，「广东省下所有区县」是一次索引查询，而不是一趟递归 CTE。
 *
 * <p><b>层级与父子深度是解耦的</b>，别按「三级严格套娃」写查询：117 个区县级行政区
 * <b>直接挂在省级下面</b>——直辖市的区（东城区 → 北京市）、省直辖县级市（济源市 → 河南省）、
 * 兵团师市（石河子市 → 新疆）。这类行的 {@code cityCode} 为 {@code null}，
 * 而 {@code districtLevel} 仍是 3（它说的是行政级别，不是树深）。
 * 民政部口径里<b>不存在</b>「市辖区」「省直辖县级行政区划」这类占位节点（那是统计局口径的产物），
 * 所以也别为了凑三级去合成它们。
 *
 * <p><b>数据从哪来、怎么再生</b>：见 {@code activity-console} 的
 * {@code DistrictSeeder}（落库入口）与 {@code examples/district-data/}（加工脚本 + 上游出处）。
 */
@Entity
@Table(name = "sys_district", indexes = {
        @Index(name = "idx_district_parent", columnList = "parent_code"),
        @Index(name = "idx_district_province", columnList = "province_code"),
        @Index(name = "idx_district_city", columnList = "city_code"),
        @Index(name = "idx_district_level", columnList = "district_level")
})
public class DistrictEntity {

    /** 6 位行政区划代码，如 {@code 440305}。天然主键：它本身就是所有查询的入口，也正是 {@code userDistrictId} 携带的值。 */
    @Id
    @Column(name = "code", length = 6, nullable = false)
    private String code;

    /** 全称，如「南山区」。 */
    @Column(name = "name", length = 64, nullable = false)
    private String name;

    /** 简称，如「南山」。前端做级联选择器时列表更短，搜索也更容易命中。 */
    @Column(name = "short_name", length = 64, nullable = false)
    private String shortName;

    /**
     * 1=省级 2=地市级 3=区县级。
     *
     * <p>列名刻意<b>不叫 {@code level}</b>：那是 Oracle 的保留字、也在若干数据库的关键字表里，
     * 本项目 MySQL / H2 两套 DDL 都由 Hibernate 生成，不值得为一个字段名去赌方言引号。
     */
    @Column(name = "district_level", nullable = false)
    private Integer districtLevel;

    /** 上级代码；省级为 {@code null}。 */
    @Column(name = "parent_code", length = 6)
    private String parentCode;

    /** 所属省级代码（<b>含自身</b>：省级行此列 = 自己的 code）。 */
    @Column(name = "province_code", length = 6, nullable = false)
    private String provinceCode;

    /** 所属地市级代码（<b>含自身</b>：地市级行此列 = 自己的 code；省级行为 {@code null}）。 */
    @Column(name = "city_code", length = 6)
    private String cityCode;

    /** 各级全称用 {@code /} 连接，如「广东省/深圳市/南山区」。直辖市等上下级同名的相邻重复段只保留一个。 */
    @Column(name = "full_name", length = 192, nullable = false)
    private String fullName;

    /** 全拼，空格分词，如 {@code nan shan}。给前端做拼音搜索用。 */
    @Column(name = "pinyin", length = 128)
    private String pinyin;

    /** 拼音首字母，如 {@code n}。字母索引/分组用；上游给不出字母时为空。 */
    @Column(name = "pinyin_initial", length = 8)
    private String pinyinInitial;

    /** 展示序号，保持上游数据集的自然顺序（大体按行政区划代码由北到南）。 */
    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;

    public DistrictEntity() {}

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getShortName() { return shortName; }
    public void setShortName(String shortName) { this.shortName = shortName; }

    public Integer getDistrictLevel() { return districtLevel; }
    public void setDistrictLevel(Integer districtLevel) { this.districtLevel = districtLevel; }

    public String getParentCode() { return parentCode; }
    public void setParentCode(String parentCode) { this.parentCode = parentCode; }

    public String getProvinceCode() { return provinceCode; }
    public void setProvinceCode(String provinceCode) { this.provinceCode = provinceCode; }

    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getPinyin() { return pinyin; }
    public void setPinyin(String pinyin) { this.pinyin = pinyin; }

    public String getPinyinInitial() { return pinyinInitial; }
    public void setPinyinInitial(String pinyinInitial) { this.pinyinInitial = pinyinInitial; }

    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }
}
