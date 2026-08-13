package com.lrj.drools.activity.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * 行政区划字典的仓库。
 *
 * <p>这里<b>刻意是可写的 {@code JpaRepository}</b>（而不是读路径那套 {@code *ReadRepository}）：
 * 字典要由 console 的 {@code DistrictSeeder} 落库，而 console 本来就是本仓库唯一的写入口 + 唯一 DDL 执行者。
 * 决策热路径不读本表——{@code userDistrictId} 是请求带进来的值，资格判定直接比字符串，不需要翻字典，
 * 所以它也不该出现在 {@code DecisionDataLoader} / {@code DecisionSnapshotBuilder} 的字段里
 * （{@code DecisionReadRepositoryGuardTest} 守着这条）。
 */
public interface DistrictRepository extends JpaRepository<DistrictEntity, String> {

    /** 按层级取（1=省级 2=地市级 3=区县级）。级联选择器的第一级。 */
    List<DistrictEntity> findByDistrictLevelOrderBySortNoAsc(Integer districtLevel);

    /** 取某一级的下级。级联选择器的第二、三级。 */
    List<DistrictEntity> findByParentCodeOrderBySortNoAsc(String parentCode);

    /**
     * 取这些省下的**全部**行政区（含省级自身）。
     *
     * <p>{@code province_code} 是**含自身**的祖先冗余列，所以一条 IN 就能拿到整棵子树，
     * 不用递归——这正是当初加那两列冗余的理由。地域投放展开（选了「广东省」要展开成
     * 广东省 + 21 个市 + 122 个区县）走的就是这条。
     */
    List<DistrictEntity> findByProvinceCodeIn(Collection<String> provinceCodes);

    /** 同上，取这些地市下的全部行政区（含地市级自身）。 */
    List<DistrictEntity> findByCityCodeIn(Collection<String> cityCodes);
}
