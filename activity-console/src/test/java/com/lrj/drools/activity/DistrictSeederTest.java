package com.lrj.drools.activity;

import com.lrj.drools.activity.persistence.DistrictEntity;
import com.lrj.drools.activity.persistence.DistrictRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行政区划字典落库的验收 + 换数据源的防线。
 *
 * <p>这张表是纯数据，没有分支逻辑，所以测试守的不是代码而是<b>数据本身的可信度</b>：
 * <ol>
 *   <li><b>双向金丝雀</b>——{@code 500157 两江新区} 必须<b>在</b>，{@code 500105 江北区} /
 *       {@code 500112 渝北区} 必须<b>不在</b>。这三条钉的是同一次调整：2025-11-06 国务院批复
 *       撤销江北区、渝北区设立两江新区，民政部随即废止那两个代码并编制 500157
 *       （重庆市人民政府 2025-11-07 公告、重庆市民政局 2025-12-05 代码变更公告）。
 *       <b>只查"该有的在不在"是不够的</b>：网上流传最广的那几份行政区划数据集停更在 2023 年，
 *       它们该有的都有，问题是<b>该没的还在</b>——一份把已撤销行政区当在册发出来的字典，
 *       会让运营把活动投到不存在的区上，而且全链路不会报任何错。</li>
 *   <li><b>祖先链闭合</b>——每一行的 parent / province / city 都必须指向本表里真实存在的行。
 *       字典自身断链的话，"把 440305 翻译成广东省/深圳市/南山区"这件事就有一半的行做不到。</li>
 *   <li><b>行数与随包 CSV 一致</b>——落一半比不落更难发现（下次启动 count>0 就直接跳过了）。</li>
 * </ol>
 *
 * <p>注意本类是**少数几个显式打开 {@code seed-district-data} 的测试**：默认关掉是为了不让
 * 其余每个 Spring 上下文都白插 3000+ 行。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:district;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "activity.marketing.seed-demo-data=false",
        "activity.marketing.seed-district-data=true",
        "activity.tenant.dev-default-enabled=true"
})
@DisplayName("行政区划字典：随包 CSV 落库、祖先链闭合、真实市辖区一个不少")
class DistrictSeederTest {

    @Autowired DistrictRepository repo;
    @Autowired DistrictSeeder seeder;

    private static long csvDataLines() throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource(DistrictSeeder.RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            return r.lines().skip(1).filter(s -> !s.isBlank()).count();
        }
    }

    @Test
    @DisplayName("启动即落库，行数与随包 CSV 逐行对齐")
    void seedsEveryRowFromCsv() throws IOException {
        assertEquals(csvDataLines(), repo.count(), "落库行数与随包 CSV 对不上（落了一半？）");
    }

    @Test
    @DisplayName("金丝雀·正向：当期真实存在的行政区必须在，且逐列正确")
    void currentDivisionsArePresent() {
        // 2025-11 重庆调整的产物：两江新区直接挂在直辖市下面，没有地市级祖先。
        assertDistrict("500157", "两江新区", 3, "500000", "500000", null, "重庆市/两江新区");
        assertDistrict("110101", "东城区", 3, "110000", "110000", null, "北京市/东城区");
        assertDistrict("440305", "南山区", 3, "440300", "440000", "440300", "广东省/深圳市/南山区");
        assertDistrict("440300", "深圳市", 2, "440000", "440000", "440300", "广东省/深圳市");
        assertDistrict("440000", "广东省", 1, null, "440000", null, "广东省");
    }

    @Test
    @DisplayName("金丝雀·反向：2025-11 已撤销并被民政部废止代码的行政区不许还在表里")
    void abolishedDivisionsAreGone() {
        for (String code : List.of("500105", "500112")) {
            assertTrue(repo.findById(code).isEmpty(),
                    code + " 是 2025-11 撤销、民政部已废止的代码（江北区/渝北区 → 两江新区 500157）；"
                            + "它还在表里说明数据集换回了停更于 2023 年的旧口径");
        }
    }

    /** 逐列断言一行，顺带守住 seeder 里那串手写 INSERT 的列序没有与实体映射错位。 */
    private void assertDistrict(String code, String name, int level,
                                String parent, String province, String city, String fullName) {
        DistrictEntity d = repo.findById(code).orElse(null);
        assertNotNull(d, "字典里缺 " + code + " " + name);
        assertEquals(name, d.getName(), code + " 名称不对");
        assertEquals(level, d.getDistrictLevel(), code + " 层级不对");
        assertEquals(parent, d.getParentCode(), code + " 上级不对");
        assertEquals(province, d.getProvinceCode(), code + " 所属省级不对");
        assertEquals(city, d.getCityCode(), code + " 所属地市不对");
        assertEquals(fullName, d.getFullName(), code + " 全路径不对");
        assertFalse(d.getShortName().isBlank(), code + " 简称为空");
        assertTrue(d.getSortNo() > 0, code + " 排序号未落值");
    }

    @Test
    @DisplayName("祖先链闭合：parent / province / city 都指向本表真实存在的行")
    void ancestryResolvesForEveryRow() {
        Map<String, DistrictEntity> all = repo.findAll().stream()
                .collect(Collectors.toMap(DistrictEntity::getCode, Function.identity()));

        for (DistrictEntity d : all.values()) {
            String at = d.getCode() + " " + d.getName();
            assertEquals(6, d.getCode().length(), at + " 代码不是 6 位");
            assertTrue(all.containsKey(d.getProvinceCode()), at + " 的省级代码悬空");
            assertEquals(1, all.get(d.getProvinceCode()).getDistrictLevel(), at + " 的省级代码指向的不是省级行");

            if (d.getDistrictLevel() == 1) {
                assertNull(d.getParentCode(), at + " 是省级却有上级");
                assertNull(d.getCityCode(), at + " 是省级却有所属地市");
                assertEquals(d.getCode(), d.getProvinceCode(), at + " 省级行的省级代码应是自己");
                continue;
            }
            DistrictEntity parent = all.get(d.getParentCode());
            assertNotNull(parent, at + " 的上级 " + d.getParentCode() + " 不在字典里");
            // 只要求父级更高，**不要求相邻**：区县直接挂省级是合法形态（直辖市 / 省直辖县级市 / 兵团师市）。
            assertTrue(parent.getDistrictLevel() < d.getDistrictLevel(), at + " 的上级层级不比自己高");
            if (d.getCityCode() != null) {
                DistrictEntity city = all.get(d.getCityCode());
                assertNotNull(city, at + " 的所属地市代码悬空");
                assertEquals(2, city.getDistrictLevel(), at + " 的所属地市指向的不是地市级行");
            }
        }
    }

    @Test
    @DisplayName("三级齐备：省级 34 行（31 省 + 台港澳），地市级与区县级量级正常")
    void allThreeLevelsAreLoaded() {
        List<DistrictEntity> provinces = repo.findByDistrictLevelOrderBySortNoAsc(1);
        assertEquals(34, provinces.size(), "省级行数应为 31 省 + 台港澳 3 行");
        assertTrue(repo.findByDistrictLevelOrderBySortNoAsc(2).size() > 300, "地市级明显偏少");
        List<DistrictEntity> counties = repo.findByDistrictLevelOrderBySortNoAsc(3);
        assertTrue(counties.size() > 2800, "区县级明显偏少");
        // 层级与树深解耦：这批「区县直挂省级」的行是刻意的，不是数据缺陷（见 DistrictEntity 类注释）。
        assertTrue(counties.stream().anyMatch(d -> d.getCityCode() == null),
                "直辖市/省直辖县级市/兵团师市这类直挂省级的区县一个都没有，数据模型被改了？");
        // 级联选择器的第二跳：广东省下面必须能列出深圳。
        assertTrue(repo.findByParentCodeOrderBySortNoAsc("440000").stream()
                        .anyMatch(d -> "440300".equals(d.getCode())),
                "广东省下列不出深圳市");
    }

    @Test
    @DisplayName("幂等：再跑一次不会插重复，也不会清表重灌")
    void rerunIsIdempotent() throws Exception {
        long before = repo.count();
        seeder.run();
        assertEquals(before, repo.count(), "重复执行把字典插重了");
    }
}
