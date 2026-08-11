package com.lrj.drools.activity.domain;

/**
 * <b>这次决策的物料是从哪来的。</b>
 *
 * <p><b>为什么它必须进业务响应而不是只当指标</b>：{@code activity.decision.source} 回答的是
 * 「整体上多少比例走了快照」，而运营/QA 在验证页上问的是「<b>我这一次</b>看到的结论，是照着
 * 数据库现状算的，还是照着一份可能落后的快照算的」。后者只能由响应自己回答——
 * 此前这个信息在 {@code DecisionDataLoader.load} 里活了三行就死了，取数层到编排层之间
 * 根本没有位置承载它。
 *
 * <p><b>它不回答的问题</b>（别在这上面加字段，去问 {@code GET /decision/v1/snapshot}）：
 * <ul>
 *   <li>快照是什么时候建的、有多旧——那是<b>运维口径</b>，且 {@code DecisionSnapshotStore.oldestAgeSeconds}
 *       是**跨租户**统计，与决策走的 {@code forTenant} 不是同一个数，混进业务契约会让 SRE 与运营对不上账。</li>
 *   <li>某个活动<b>在不在</b>这份快照里——那是诊断端点的职责，也恰恰是「三个值全绿但活动就是不命中」
 *       这类故障唯一能说话的出口。</li>
 * </ul>
 *
 * @param source     {@code "snapshot"} = 物料来自代际快照（零数据库查询）；{@code "db"} = 逐请求查库
 * @param generation 参与本次决策的快照桶里<b>最落后的那一代</b>；{@code source=db} 时为 null。
 *                   取最小值而不是最大值，是因为这个数要回答的是「我刚发布的那次进去了没有」——
 *                   多桶时任何一个桶落后都意味着「还没全进去」
 * @param buckets    参与本次决策的快照桶数。{@code >1} 时 {@code generation} 是多个桶的下确界而非某一个桶的真值，
 *                   这个字段就是那个约定的诚实声明（一次决策会合并该租户<b>所有业务线</b>的桶）
 */
public record DecisionProvenance(String source, Long generation, int buckets) {

    public static final String SOURCE_DB = "db";
    public static final String SOURCE_SNAPSHOT = "snapshot";

    /** 走库。这是**默认值**：任何还没接上 provenance 的装配路径都该落在这里，而不是谎称快照。 */
    public static DecisionProvenance db() {
        return new DecisionProvenance(SOURCE_DB, null, 0);
    }

    public static DecisionProvenance snapshot(Long minGeneration, int buckets) {
        return new DecisionProvenance(SOURCE_SNAPSHOT, minGeneration, buckets);
    }

    public boolean fromSnapshot() {
        return SOURCE_SNAPSHOT.equals(source);
    }
}
