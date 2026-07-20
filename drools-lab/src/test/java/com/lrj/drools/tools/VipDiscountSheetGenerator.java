package com.lrj.drools.tools;

import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Step 7: 生成 VIP 折扣决策表 XLSX。
 *
 * 为什么用 JUnit 跑而不是放在 main flow?
 *   - Drools 8 不自动识别 .csv 为决策表 (启动日志会报 "No files found")
 *   - 必须用 .xls 或 .xlsx (二进制), 手写不现实
 *   - POI 已经通过 drools-decisiontables 进了项目依赖, 用它生成最干净
 *
 * 怎么跑:
 *   ./mvnw test -Dtest=VipDiscountSheetGenerator
 *
 * 生成位置: src/main/resources/rules/decision/vip-discount.xlsx
 * 业务方拿到这个 XLSX 后, 直接用 Excel/Numbers 编辑加档位 → 提交回 git → 重启生效。
 *
 * 决策表 schema 说明 (Drools 8):
 *   row 1: RuleSet | <package>
 *   row 2: (空)
 *   row 3: Import  | <fqcn 列表, 逗号分隔>
 *   row 4: (空)
 *   row 5: RuleTable <table-name>      → 后续每行数据生成一条规则: <table-name>_N
 *   row 6: 列类型 (CONDITION / ACTION / PRIORITY / NAME / ...)
 *   row 7: **对象声明** (CONDITION 列写 pattern + 绑定变量, ACTION 列写要操作的变量)
 *   row 8: 约束/方法片段 (用 $param 单值, 或 $1,$2 多值)
 *   row 9: 列标签 (给业务方看, 不影响编译)
 *   row 10+: 数据行
 *
 * 漏掉 row 7 会报 "It looks like you have snippets in the row that is meant for
 * object declarations" — 这是 Drools 8 决策表新手最常踩的坑。
 */
public class VipDiscountSheetGenerator {

    private static final Path OUTPUT = Paths.get(
            "src/main/resources/rules/decision/vip-discount.xls");

    @Test
    public void generate() throws Exception {
        Files.createDirectories(OUTPUT.getParent());

        try (HSSFWorkbook wb = new HSSFWorkbook()) {
            HSSFSheet sheet = wb.createSheet("VipDiscount");
            int r = 0;
            row(sheet, r++, "RuleSet", "rules.decision");
            r++;
            row(sheet, r++, "Import", "com.lrj.drools.domain.Cart");
            r++;
            row(sheet, r++, "RuleTable VipDiscountTable");
            row(sheet, r++, "CONDITION", "ACTION");
            // 对象声明: CONDITION 列写 pattern, ACTION 列写要操作的绑定变量
            row(sheet, r++, "$cart: Cart()", "$cart");
            // 约束/方法片段: $param = 该列单值, $1/$2 = 单元格内逗号切分的多值
            row(sheet, r++,
                    "customer.vipLevel == $param",
                    "applyRatioDiscount($1, \"$2\")");
            row(sheet, r++, "VIP Level", "Ratio + Reason");
            row(sheet, r++, "1", "0.95, VIP 1 折扣 (来自决策表)");
            row(sheet, r++, "2", "0.9, VIP 2 折扣 (来自决策表)");
            row(sheet, r++, "3", "0.85, VIP 3 折扣 (来自决策表)");
            row(sheet, r++, "4", "0.8, VIP 4 折扣 (来自决策表 - 新档位无需改代码)");

            try (FileOutputStream out = new FileOutputStream(OUTPUT.toFile())) {
                wb.write(out);
            }
        }
        System.out.println("[VipDiscountSheetGenerator] wrote " + OUTPUT.toAbsolutePath());
    }

    private static void row(HSSFSheet sheet, int rowIdx, String... cells) {
        var row = sheet.createRow(rowIdx);
        for (int i = 0; i < cells.length; i++) {
            row.createCell(i).setCellValue(cells[i]);
        }
    }
}
