package com.lrj.drools.service;

import com.lrj.drools.domain.Order;
import org.drools.template.ObjectDataCompiler;
import org.kie.api.KieBase;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Step 23: 规则模板 .drt —— 模板 + 数据行 → 生成 DRL。
 *
 * 跟 Step 7 决策表的关系：决策表（.xls）本质也是"模板的表格皮"，drools-decisiontables 内部
 * 就是把表格行喂给同一套模板引擎。这里直接用底层 `ObjectDataCompiler`：
 *   1. 把每档折扣配置（minAmount/maxAmount/discount）做成一个 Map 数据行
 *   2. ObjectDataCompiler.compile(数据行集合, 模板流) → 展开成 DRL 文本（每行一条 rule）
 *   3. 走 Step 9 的 KieHelper 路径把 DRL 编译成 KieBase 跑
 *
 * 价值：规则结构固定、只有阈值/参数在变时，用模板 + 数据比手写 N 条近乎重复的 DRL 更好维护，
 * 且数据行可以来自 DB / 请求（"规则即数据"）。返回体带上生成的 DRL，方便直接看到展开结果。
 */
@Service
public class TemplateService {

    public Result generate(Order order, List<Map<String, Object>> tierRows) {
        // 1~2. 模板 + 数据行 → DRL
        String drl;
        try (InputStream tpl = getClass().getResourceAsStream("/templates/discount-template.drt")) {
            if (tpl == null) {
                throw new IllegalStateException("找不到模板 /templates/discount-template.drt");
            }
            drl = new ObjectDataCompiler().compile(tierRows, tpl);
        } catch (IOException e) {
            throw new UncheckedIOException("读取规则模板失败", e);
        }

        // 3. 生成的 DRL 走 Step 9 的 KieHelper 编译（编译失败带行号）
        KieHelper helper = new KieHelper();
        helper.addContent(drl, ResourceType.DRL);
        Results results = helper.verify();
        if (results.hasMessages(Message.Level.ERROR)) {
            String detail = results.getMessages(Message.Level.ERROR).stream()
                    .map(m -> "line " + m.getLine() + ": " + m.getText())
                    .collect(Collectors.joining("\n"));
            throw new IllegalArgumentException("模板生成的 DRL 编译失败:\n" + detail + "\n--- 生成的 DRL ---\n" + drl);
        }

        KieBase base = helper.build();
        KieSession session = base.newKieSession();
        try {
            session.insert(order);
            int fired = session.fireAllRules();
            return new Result(order, fired, drl);
        } finally {
            session.dispose();
        }
    }

    /** 把请求里的档位配置转成模板数据行（key 与模板 header 列名对齐）。 */
    public static List<Map<String, Object>> toRows(List<Tier> tiers) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Tier t : tiers) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("minAmount", t.minAmount());
            row.put("maxAmount", t.maxAmount());
            row.put("discount", t.discount());
            rows.add(row);
        }
        return rows;
    }

    public record Tier(double minAmount, double maxAmount, double discount) {}

    public record Result(Order order, int firedCount, String generatedDrl) {}
}
