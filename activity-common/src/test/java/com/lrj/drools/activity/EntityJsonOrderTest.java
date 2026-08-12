package com.lrj.drools.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.drools.activity.persistence.ActivityGrantEntity;
import com.lrj.drools.activity.persistence.ActivityManageEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 实体序列化出来的 <b>JSON 键顺序</b>必须以身份字段打头。
 *
 * <p><b>为什么需要这条测试</b>：R13 把 {@code tenantId} / {@code isDel} / 双时间戳收进了两层
 * {@code @MappedSuperclass}。这消掉了 66 处重复，但带来一个没人声明过的副作用——
 * Jackson 默认把<b>超类属性排在子类之前</b>，于是 {@code /activity-marketing/list}、
 * {@code /detail}、{@code /grants} 的每个实体对象从
 * {@code {"id":…,"activityId":…}} 变成了 {@code {"tenantId":…,"createdStime":…,…}}。
 *
 * <p>字段名与取值一个字节没变，前端按键取值也不受影响（{@code 285} 个前端用例全绿）。
 * 但这仍是响应体的一次<b>静默</b>改变：任何对响应做 hash / ETag / 快照比对的东西都会飘，
 * 而飘的时候没有任何信号。把顺序钉死在测试里，是让「继承结构一变、响应就变」这件事
 * 下次发生时<b>响亮地失败</b>，而不是留给下游去发现。
 *
 * <p>钉的是<b>前缀</b>而非全序：新增业务字段是常态，不该因为加了一个字段就红；
 * 而「身份字段是否还在最前面」才是这条测试真正关心的不变量。
 */
@DisplayName("实体 JSON：身份字段必须排在继承来的公共字段之前")
class EntityJsonOrderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> keysOf(Object entity) throws Exception {
        List<String> keys = new ArrayList<>();
        Iterator<String> it = MAPPER.valueToTree(entity).fieldNames();
        while (it.hasNext()) keys.add(it.next());
        return keys;
    }

    @Test
    @DisplayName("ActivityManageEntity 以 id / activityId 打头")
    void manageEntityLeadsWithIdentity() throws Exception {
        List<String> keys = keysOf(new ActivityManageEntity());
        assertEquals(List.of("id", "activityId"), keys.subList(0, 2),
                "实体 JSON 的前两个键应当是身份字段，实际序是 " + keys
                        + "。这通常意味着有人动了 @MappedSuperclass 继承结构——"
                        + "Jackson 会把超类属性排到子类之前，于是所有列表/详情响应的键序一起变。"
                        + "字段名与取值不受影响，但对响应做 hash/ETag/快照比对的下游会静默飘。");
    }

    @Test
    @DisplayName("ActivityGrantEntity 同样以 id 打头（它没有 is_del 列，走另一层超类）")
    void grantEntityLeadsWithIdentity() throws Exception {
        List<String> keys = keysOf(new ActivityGrantEntity());
        assertEquals("id", keys.get(0),
                "发放流水的 JSON 应以 id 打头，实际序是 " + keys);
    }
}
