package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.DistrictView;
import com.lrj.drools.activity.persistence.DistrictEntity;
import com.lrj.drools.activity.persistence.DistrictRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 行政区划字典的只读查询。控制台地域选择器与「投放地域 → 资格条件」的展开都从这里取数。
 *
 * <p><b>为什么单开一个 service 而不是让 controller 直接注入 repository</b>：
 * {@code ActivityMarketingController} 目前持有 5 个依赖、**0 个 repository**，
 * 直接注入会开第一个口子。{@code /field-dict} 也不是反例——它注入的是 {@code RuleSchemaRegistry}（引擎 bean），
 * 仍然是「controller 只调 bean」。分层模板照 {@link GenerationService}：
 * {@code @Service} + 构造器注入 + 只读方法不加 {@code @Transactional}。
 *
 * <p><b>没有做进程内缓存</b>是刻意的：字典 3212 行、一次全量查询几毫秒，而缓存要配套失效
 * （seeder 重灌、换数据集），失效写错的代价是「运营看到的字典与库里的不一致」——
 * 比省那几毫秒贵得多。真需要时应该加 HTTP 层的 {@code ETag}，而不是在这里存一份。
 */
@Service
public class DistrictQueryService {

    /**
     * 全量下发的固定排序。
     *
     * <p><b>这行不能省</b>：仓库里那两个派生方法自带 {@code OrderBySortNoAsc}，但全量走的是
     * {@code findAll()}，它**无序**。{@link DistrictView} 刻意不传 {@code sortNo}，理由是
     * 「顺序已经体现为数组顺序」——那条理由只有在这里显式排序时才成立。
     */
    private static final Sort BY_SORT_NO = Sort.by(Sort.Direction.ASC, "sortNo");

    private final DistrictRepository repo;

    public DistrictQueryService(DistrictRepository repo) {
        this.repo = repo;
    }

    /** 全量字典（3212 行）。前端一次拉走，本地建索引做级联与搜索。 */
    public List<DistrictView> all() {
        return toViews(repo.findAll(BY_SORT_NO));
    }

    /** 按层级取。{@code level} 只接受 1/2/3。 */
    public List<DistrictView> byLevel(int level) {
        if (level < 1 || level > 3) {
            throw new IllegalArgumentException("level 只能是 1(省级)/2(地市级)/3(区县级)，实得 " + level);
        }
        return toViews(repo.findByDistrictLevelOrderBySortNoAsc(level));
    }

    /** 取某个行政区的下级。父级不存在或没有下级都返回空列表——「广东没有下级」不是错误。 */
    public List<DistrictView> byParent(String parentCode) {
        requireCode(parentCode);
        return toViews(repo.findByParentCodeOrderBySortNoAsc(parentCode));
    }

    /**
     * 把「所选行政区」展开成**它自己 + 全部后代**的代码集合。资格条件翻译（投放地域 → {@code userDistrictId IN (...)}）用。
     *
     * <p><b>为什么必须含各级祖先自身、而不是只展开到叶子</b>：决策请求里的 {@code userDistrictId}
     * 是调用方给什么就是什么。本仓既有取值全是**省级码**（{@code playbooks.ts} 的地域定向模板与
     * e2e 都用 {@code 310000}），而真实业务系统多半送区县码。只展开到叶子的话，
     * 带 {@code 440000} 的请求在「投放广东」的活动上**一律不命中**——
     * 而且失败方式是「少发钱」，是最难被发现的那一类。
     *
     * <p>查询次数固定 ≤3（按 id 取所选行 + 省子树 + 市子树），与所选个数无关。
     */
    public Set<String> expandWithDescendants(Collection<String> codes) {
        Set<String> out = new LinkedHashSet<>();
        if (codes == null || codes.isEmpty()) return out;

        List<String> wanted = codes.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (wanted.isEmpty()) return out;

        List<String> provinces = new ArrayList<>();
        List<String> cities = new ArrayList<>();
        for (DistrictEntity e : repo.findAllById(wanted)) {
            out.add(e.getCode());
            Integer lv = e.getDistrictLevel();
            if (lv != null && lv == 1) provinces.add(e.getCode());
            else if (lv != null && lv == 2) cities.add(e.getCode());
            // level 3 没有下级，自身已在 out 里
        }
        // 字典里查不到的码（如 2025-11 撤销的 500105）原样保留：让它继续参与匹配，
        // 总比因为字典换代把一条存量投放规则悄悄删掉强。
        out.addAll(wanted);

        if (!provinces.isEmpty()) {
            for (DistrictEntity e : repo.findByProvinceCodeIn(provinces)) out.add(e.getCode());
        }
        if (!cities.isEmpty()) {
            for (DistrictEntity e : repo.findByCityCodeIn(cities)) out.add(e.getCode());
        }
        return out;
    }

    private static void requireCode(String code) {
        if (code == null || !code.matches("\\d{6}")) {
            throw new IllegalArgumentException("行政区划代码必须是 6 位数字，实得: " + code);
        }
    }

    private static List<DistrictView> toViews(List<DistrictEntity> rows) {
        List<DistrictView> out = new ArrayList<>(rows.size());
        for (DistrictEntity e : rows) out.add(DistrictView.from(e));
        return out;
    }
}
