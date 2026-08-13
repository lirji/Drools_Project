package com.lrj.drools.activity;

import com.lrj.drools.activity.domain.ActivityCreateRequest;
import com.lrj.drools.activity.domain.ConditionNode;
import com.lrj.drools.activity.domain.DistrictView;
import com.lrj.drools.activity.persistence.ActivityConditionEntity;
import com.lrj.drools.activity.persistence.ActivityConditionRepository;
import com.lrj.drools.activity.service.ActivityMarketingService;
import com.lrj.drools.activity.service.ActivityMarketingService.CreateResult;
import com.lrj.drools.activity.service.DistrictQueryService;
import com.lrj.drools.activity.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行政区字典查询 + 「投放地域 → 资格条件」翻译。
 *
 * <p>这条链路补的是本仓审计里编号 <b>B2「地域假开关」</b> 的洞：
 * {@code activityAreaType} / {@code districtIds} 此前能编辑、能落库、能进候选和快照，
 * 但 {@code service/} / {@code engine/} / {@code snapshot/} 三个包对这两个字段名 grep 为空——
 * <b>零读取点</b>。运营配了地域，活动照样全国发钱，详情页还把它当生效配置回显。
 *
 * <p>本测试守两件事：字典查得出来（选择器有得选），以及选中的地域<b>真的变成了资格条件</b>。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:districtscope;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-demo-data=false",
        "activity.marketing.seed-district-data=true",
        "activity.tenant.dev-default-enabled=true"
})
@DisplayName("行政区字典：查询接口 + 投放地域翻译成 userDistrictId 资格条件")
class DistrictDictAndScopeTest {

    private static final String BIZ = "districtbiz";

    @Autowired DistrictQueryService districts;
    @Autowired ActivityMarketingService marketing;
    @Autowired ActivityConditionRepository conditionRepo;

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ---------------------------------------------------------------- 字典查询

    @Test
    @DisplayName("全量：3212 行、省级 34 个、按 sortNo 有序")
    void fullDictionaryIsOrdered() {
        List<DistrictView> all = districts.all();
        assertEquals(34, all.stream().filter(d -> d.level() == 1).count(), "省级应为 31 省 + 台港澳");
        assertTrue(all.size() > 3000, "全量明显偏少：" + all.size());

        // 顺序即权威：DistrictView 刻意不传 sortNo，理由是「顺序已体现为数组顺序」——
        // 那条理由只有在这里真的排了序时才成立（findAll() 本身无序）。
        assertEquals("110000", all.get(0).code(), "第一行应是北京市（sortNo=1）");
    }

    @Test
    @DisplayName("按父级取下级：广东省下有深圳市；直辖市下直接是区（层级与树深解耦）")
    void childrenByParent() {
        assertTrue(districts.byParent("440000").stream().anyMatch(d -> d.code().equals("440300")),
                "广东省下应列得出深圳市");
        // 北京市没有地市级这一层，下级直接是区县级——选择器不能假设「省→市→区」严格三层。
        List<DistrictView> beijing = districts.byParent("110000");
        assertFalse(beijing.isEmpty(), "北京市下应有区");
        assertTrue(beijing.stream().allMatch(d -> d.level() == 3), "直辖市的下级应直接是区县级");
    }

    @Test
    @DisplayName("参数非法一律 IllegalArgumentException（→ 400，不是 500）")
    void badParamsRejected() {
        assertThrows(IllegalArgumentException.class, () -> districts.byLevel(9));
        assertThrows(IllegalArgumentException.class, () -> districts.byLevel(0));
        assertThrows(IllegalArgumentException.class, () -> districts.byParent("44"));
    }

    @Test
    @DisplayName("拼音已去空格并归一化成非空串（前端搜索是 includes，可空会炸）")
    void pinyinNormalised() {
        DistrictView shenzhen = districts.all().stream()
                .filter(d -> d.code().equals("440300")).findFirst().orElseThrow();
        assertEquals("shenzhen", shenzhen.pinyin(), "库里是空格分词的「shen zhen」，出参应去空格");
        assertTrue(districts.all().stream().allMatch(d -> d.pinyin() != null && d.pinyinInitial() != null),
                "拼音字段一律非 null");
    }

    @Test
    @DisplayName("首字母是**逐字**取的（gd/sz），不是库里那个单字母——否则前端那条分支恒不命中")
    void pinyinInitialsArePerSyllable() {
        // 库里 pinyin_initial 全表只有 1 个字符（广东省 = g）。前端的分支是
        // `t.length > 1 && pinyinInitial.startsWith(t)`，拿 1 个字符去 startsWith("gd") 恒为假，
        // 「支持首字母搜索」于是一直是句空话。这里钉住逐字首字母。
        assertEquals("gd", initialOf("440000"), "广东省应为 gd");
        assertEquals("sz", initialOf("440300"), "深圳市应为 sz");
        assertEquals("ns", initialOf("440305"), "南山区应为 ns");
        assertEquals("ljxq", initialOf("500157"), "两江新区应为 ljxq");

        long multi = districts.all().stream().filter(d -> d.pinyinInitial().length() > 1).count();
        assertTrue(multi > 3000, "绝大多数行政区是多字名，逐字首字母应普遍多于 1 个字符，实得 " + multi);
    }

    private String initialOf(String code) {
        return districts.all().stream().filter(d -> d.code().equals(code))
                .findFirst().orElseThrow().pinyinInitial();
    }

    // ---------------------------------------------------------------- 展开

    @Test
    @DisplayName("展开含自身与全部后代——不是只到叶子")
    void expandIncludesSelfAndAllDescendants() {
        Set<String> gd = districts.expandWithDescendants(List.of("440000"));
        assertTrue(gd.contains("440000"), "省级码自身必须在——本仓既有 userDistrictId 取值就是省级码");
        assertTrue(gd.contains("440300"), "地市级必须在");
        assertTrue(gd.contains("440305"), "区县级必须在");
        assertTrue(gd.size() > 100, "广东省展开后应有百余个：" + gd.size());

        Set<String> sz = districts.expandWithDescendants(List.of("440300"));
        assertTrue(sz.contains("440300") && sz.contains("440305"), "地市级展开应含自身与下辖区县");
        assertFalse(sz.contains("440000"), "展开只向下，不向上");
    }

    @Test
    @DisplayName("字典里查不到的码原样保留（如 2025-11 撤销的 500105）")
    void unknownCodesSurviveExpansion() {
        Set<String> out = districts.expandWithDescendants(List.of("500105", "440305"));
        assertTrue(out.contains("500105"),
                "已撤销代码必须原样保留——因为字典换代就悄悄删掉一条存量投放规则，比留着更危险");
        assertTrue(out.contains("440305"));
    }

    // ---------------------------------------------------------------- 翻译成资格条件

    @Test
    @DisplayName("只投广东、不配其它条件：条件行必须被建出来（saveCondition 对空树会早返回）")
    void districtOnlyStillCreatesConditionRow() {
        TenantContext.set("acme");
        CreateResult r = marketing.create(req("district-only", 2, "440000", null));

        ConditionNode tree = storedTree(r);
        assertNotNull(tree, "这是本功能最典型的用法，条件行一个都不能少");
        List<ConditionNode> children = tree.getChildren();
        assertEquals(1, children.size());
        ConditionNode leaf = children.get(0);
        assertEquals("userDistrictId", leaf.getField());
        assertEquals("in", leaf.getOp());
        assertTrue(leaf.isDistrictGenerated(), "自动合成的节点必须带来源标记，否则回读时剥不掉");
        assertTrue(((List<?>) leaf.getValue()).contains("440305"), "广东下辖区县应在取值域里");
        assertTrue(((List<?>) leaf.getValue()).contains("440000"), "省级码自身也应在");
    }

    @Test
    @DisplayName("与运营自己的条件 AND，且并进现有 AND 组不加深树")
    void mergesIntoExistingAndGroupWithoutDeepening() {
        TenantContext.set("acme");
        ConditionNode userTree = andGroup(leaf("orderAmount", "ge", 100));
        CreateResult r = marketing.create(req("district-merge", 2, "440300", userTree));

        ConditionNode tree = storedTree(r);
        assertEquals("AND", tree.getLogic());
        assertEquals(2, tree.getChildren().size(), "应并进同一层，而不是外面再包一层");
        assertTrue(tree.getChildren().stream().anyMatch(c -> "orderAmount".equals(c.getField())));
        assertTrue(tree.getChildren().stream().anyMatch(ConditionNode::isDistrictGenerated));
    }

    @Test
    @DisplayName("幂等：把带注入节点的树再存一次，叶子不翻倍、树深不增长")
    void reSaveIsIdempotent() {
        TenantContext.set("acme");
        CreateResult first = marketing.create(req("district-idem", 2, "440300", andGroup(leaf("quantity", "ge", 1))));
        ConditionNode stored = storedTree(first);
        assertEquals(2, stored.getChildren().size());

        // 模拟编辑器行为：把**整份存储树**原样回传（EditorView.loadForEdit 就是这么干的）
        ActivityCreateRequest again = req("district-idem", 2, "440300", stored);
        CreateResult second = marketing.create(withId(again, first.activityId()));

        ConditionNode reStored = storedTree(second);
        assertEquals(2, reStored.getChildren().size(),
                "再存一次仍应是「用户条件 + 一条地域条件」；翻倍说明没剥掉上一次的注入节点");
        assertEquals(1, reStored.getChildren().stream().filter(ConditionNode::isDistrictGenerated).count());
    }

    @Test
    @DisplayName("全国（areaType=1）不注入任何地域条件")
    void nationwideInjectsNothing() {
        TenantContext.set("acme");
        CreateResult r = marketing.create(req("district-nationwide", 1, null, null));
        assertNull(storedTree(r), "全国活动不该凭空多出一条资格条件");
    }

    // ---------------------------------------------------------------- 列宽

    @Test
    @DisplayName("投放地域超列宽是 400 不是 500（district_ids 是 varchar(1024)）")
    void tooManyDistrictsRejectedAsBadRequest() {
        TenantContext.set("acme");
        assertThrows(IllegalArgumentException.class,
                () -> marketing.create(req("district-overflow", 2, csvCodes(147), null)),
                "147 个码 = 1028 字符 > 1024。没有前置校验时它会在 saveAndFlush 炸成 500，"
                        + "而 500 会让调用方无限重试一个永远不会成功的请求");
        // 146 个（1021 字符）是边界内，必须放行
        CreateResult ok = marketing.create(req("district-limit", 2, csvCodes(146), null));
        assertNotNull(ok.activityId());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 按<b>生产侧口径</b>把存下来的条件树读回来——即 {@code DecisionDataLoader.parseTree} 用的那种
     * 关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES} 的 mapper。原因见那边的注释：
     * {@code ConditionNode.isGroup()} 是派生 boolean getter，序列化时会多写一个 {@code "group"} 键，
     * 而它没有 setter——用裸 ObjectMapper 读会直接抛。这里若用严格 mapper，测的就不是生产行为了。
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper LENIENT =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ConditionNode storedTree(CreateResult r) {
        List<ActivityConditionEntity> rows =
                conditionRepo.findByActivityIdAndVersionAndIsDel(r.activityId(), r.version(), 0);
        if (rows.isEmpty()) return null;
        try {
            return LENIENT.readValue(rows.get(0).getConditionTreeJson(), ConditionNode.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 造 n 个各不相同的 6 位码（不必真实存在——这里测的是列宽而不是字典命中）。 */
    private static String csvCodes(int n) {
        return java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> String.format("%06d", 100000 + i))
                .collect(Collectors.joining(","));
    }

    private static ConditionNode leaf(String field, String op, Object value) {
        ConditionNode n = new ConditionNode();
        n.setField(field); n.setOp(op); n.setValue(value);
        return n;
    }

    private static ConditionNode andGroup(ConditionNode... children) {
        ConditionNode g = new ConditionNode();
        g.setLogic("AND");
        g.setChildren(new java.util.ArrayList<>(List.of(children)));
        return g;
    }

    private static ActivityCreateRequest req(String name, int areaType, String districtIds, ConditionNode cond) {
        long hAgo = System.currentTimeMillis() - 3_600_000L;
        long hLater = System.currentTimeMillis() + 3_600_000L;
        return new ActivityCreateRequest(
                null, null, name, BIZ, 1, name,
                hAgo, hLater, areaType, districtIds, 1, 100,
                1, new BigDecimal("50"), "元", null, "MAX",
                cond, List.of(new ActivityCreateRequest.SpuBinding(1, 9001L)), null, null);
    }

    private static ActivityCreateRequest withId(ActivityCreateRequest r, String activityId) {
        return new ActivityCreateRequest(
                activityId, null, r.activityName(), r.bizLine(), r.activityType(), r.activityRule(),
                r.activityStartTime(), r.activityEndTime(), r.activityAreaType(), r.districtIds(),
                r.priority(), r.inventory(), r.redPackageTakeType(), r.redPackageAmount(),
                r.redPackageAmountUnit(), r.redPackageRangeAmount(), r.discountStrategy(),
                r.eligibilityConditionTree(), r.spuBindings(), r.poolRefs(), r.gifts());
    }
}
