package com.lrj.drools.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.drools.activity.domain.ConditionNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>{@code condition_tree_json} 的存储形状契约。</b>这份 JSON 有两个消费方，两个都会被形状变化咬到：
 * 后端自己（理论上要能读回来）与控制台编辑器（{@code EditorView.loadForEdit} 直接 {@code JSON.parse}）。
 *
 * <p>它由 {@code ActivityMarketingService} 里一个**零配置** {@code new ObjectMapper()} 写出，
 * 也就是说 {@code JsonInclude.ALWAYS} + 全部 public getter 都进 JSON。两条已实测炸过的后果：
 *
 * <ol>
 *   <li><b>boolean getter 会凭空多出一个键</b>。{@code isGroup()} 不标 {@code @JsonIgnore} 时
 *       写出 {@code "group": false}，而本类没有 {@code setGroup}、也没开 {@code ignoreUnknown}，
 *       于是 {@code readValue} 抛 {@code UnrecognizedPropertyException}——**自己写的 JSON 自己读不回来**。
 *       今天后端没有回读路径，所以它一直是哑的，只会在有人第一次去读的时候炸。</li>
 *   <li><b>叶子节点带着 {@code "logic": null} 落库</b>。前端的分组判别因此不能写成
 *       {@code logic !== undefined}（{@code null !== undefined} 为真 → 每片叶子都被判成分组 →
 *       {@code children} 是 {@code null} → TypeError → 被 {@code loadForEdit} 的 catch 吞掉 →
 *       **存量活动的资格条件树整棵消失，再保存一次就真的没了**）。
 *       前端那侧由 {@code shared/types.ts} 的注释与 {@code districtLogic.test.ts} 守，
 *       这里钉住的是「后端确实会这么写」这个前提——哪天它不这么写了，那边的注释就该跟着改。</li>
 * </ol>
 */
@DisplayName("condition_tree_json 的存储形状：写得出去，也读得回来")
class ConditionNodeJsonShapeTest {

    /** 必须与 {@code ActivityMarketingService.objectMapper} 同配置（都是零配置）。 */
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("不写 group 键——它没有 setter，写进去就再也读不回来")
    void doesNotSerializeDerivedGroupFlag() throws Exception {
        String json = mapper.writeValueAsString(tree());
        assertFalse(json.contains("\"group\""),
                "isGroup() 漏了 @JsonIgnore：多出来的 group 键会让 readValue 抛 UnrecognizedPropertyException\n" + json);
    }

    @Test
    @DisplayName("写出去的 JSON 能原样读回来，且结构不变")
    void roundTripsThroughJackson() throws Exception {
        String json = mapper.writeValueAsString(tree());
        ConditionNode back = assertDoesNotThrow(() -> mapper.readValue(json, ConditionNode.class),
                "写得出去、读不回来 —— 见本类头注释第 1 条");

        assertTrue(back.isGroup(), "根节点应仍是分组");
        assertEquals(2, back.getChildren().size());
        assertFalse(back.getChildren().get(0).isGroup(), "叶子不该被读成分组");
        assertEquals("userLevel", back.getChildren().get(0).getField());
        assertEquals(ConditionNode.SOURCE_DISTRICT, back.getChildren().get(1).getSource(),
                "source 是后端幂等合成的唯一依据，往返必须保住");
    }

    @Test
    @DisplayName("叶子确实带着 logic:null 落库——前端的分组判别不能只判 undefined")
    void leavesCarryExplicitNullLogic() throws Exception {
        String json = mapper.writeValueAsString(tree());
        assertTrue(json.contains("\"logic\":null"),
                "前提变了：叶子不再写 logic:null。shared/types.ts 的 isGroup 注释要同步更新\n" + json);
    }

    private static ConditionNode tree() {
        ConditionNode userLeaf = new ConditionNode();
        userLeaf.setField("userLevel");
        userLeaf.setOp(">=");
        userLeaf.setValue("3");

        ConditionNode districtLeaf = new ConditionNode();
        districtLeaf.setField("userDistrictId");
        districtLeaf.setOp("in");
        districtLeaf.setValue(List.of("440000", "440300"));
        districtLeaf.setSource(ConditionNode.SOURCE_DISTRICT);

        ConditionNode root = new ConditionNode();
        root.setLogic("AND");
        root.setChildren(List.of(userLeaf, districtLeaf));
        return root;
    }
}
