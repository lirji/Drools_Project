package com.lrj.drools.activity.domain;

/**
 * 一次决策的<b>档位</b>：热路径还是试算。
 *
 * <p><b>为什么不是 boolean</b>：改造前四个决策入口各有一个「省掉 explain」的便捷重载，
 * 而两个姊妹服务的默认值<b>方向相反</b>——{@code ActivityQueryService.spuDiscount/buyAndGetGifts}
 * 默认 {@code false}（热路径），{@code AddOnPurchaseService.options/quote} 默认 {@code true}（试算）。
 * 六个决策入口里有四个走的是默认值，读者在调用点上<b>无法本地推理这次决策到底是哪一档</b>；
 * 今天没出事只是因为「默认值恰好对着自己那一侧的调用方」——console 调加价购、decision 调红包，
 * 两条默认值各自撞对了。任何一次跨平面复用（decision 复用 console 的某个 helper、
 * 或反过来）都会静默换档：热路径开始外泄逐候选资格明细，或试算页丢掉全部链路。
 *
 * <p>所以载重的不是这个枚举本身，是<b>那四个便捷重载被删掉了</b>：
 * 每个调用点必须显式表态，漏了就编译不过。枚举只是让这个表态在阅读时是自解释的
 * （{@code DecisionMode.HOT_PATH} 比一个裸 {@code false} 说得清楚）。
 *
 * <p><b>刻意只有两个常量</b>。不铺 none/structural/full 三档：今天没有任何调用方需要第三档，
 * 而一旦有了它就必须回答「结构性 trace 到底含哪些」——那是个没有依据的新契约。
 *
 * <p><b>与 {@code ActivityDrlBuilder} 的 {@code explain} 无关</b>：那个参数是<b>构建期</b>布尔，
 * 它改变生成的 DRL 文本，而 {@code compileOrGet} 的缓存 key 就是 {@code tenant + DRL 全文}。
 * 把它与运行期档位耦合会让同一份规则被编译两遍，故意留成 boolean。
 */
public enum DecisionMode {

    /**
     * 决策热路径（{@code /decision/v1/*}）：不 emit trace。
     *
     * <p>省掉的不只是字符串拼接与序列化：逐候选的资格淘汰明细
     * （谁被哪条门槛刷掉）本来就不该随线上决策响应外泄给下游调用方。
     */
    HOT_PATH,

    /**
     * 控制台试算（{@code /activity-marketing/*}）：产出可读链路。
     *
     * <p>运营需要看见「为什么这张券没生效」，所以这一档把资格与合并的 trace 全部带出。
     */
    EXPLAIN;

    /** 是否需要 emit trace。取代改造前散在各处的 {@code if (explain)}。 */
    public boolean explains() {
        return this == EXPLAIN;
    }
}
