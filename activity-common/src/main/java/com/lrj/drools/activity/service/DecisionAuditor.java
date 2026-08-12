package com.lrj.drools.activity.service;

import com.lrj.drools.activity.domain.DecisionProvenance;
import com.lrj.drools.activity.domain.DecisionScene;
import com.lrj.drools.activity.domain.GiftResult;
import com.lrj.drools.activity.domain.SpuDiscountRequest;
import com.lrj.drools.activity.service.ActivityQueryService.DiscountItem;
import com.lrj.drools.activity.service.ActivityQueryService.DiscountView;
import com.lrj.drools.activity.service.ActivityQueryService.GiftView;
import com.lrj.drools.activity.service.AddOnPurchaseService.AddOnOption;
import com.lrj.drools.activity.service.AddOnPurchaseService.AddOnOptions;
import com.lrj.drools.activity.service.AddOnPurchaseService.AddOnQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * <b>决策留痕</b>——三条通道共用的一份结构化日志出口。
 *
 * <p>此前这段拼装是 {@code ActivityQueryService} 的一个私有方法，且 scene 写死
 * {@code SCENE_DISCOUNT}：买赠生成了 {@code decisionId} 却从不落日志，加价购的两个
 * record 连 {@code decisionId} 分量都没有。于是「拿着 decisionId 去日志里查」这件事
 * 只在红包通道上成立，另外两条通道的 id 查出来一无所获——而客服并不知道这个区别。
 *
 * <p><b>为什么是日志而不是表</b>：decision 服务连的是只读账号，物理上写不了库
 * （{@code DecisionDdlGuardTest} 钉死这条边界）。让热路径去写库会同时毁掉
 * 「只读副本可扩」与「写面独占 DDL」两条边界。
 *
 * <p><b>为什么不用 Jackson</b>：格式刻意是**单行 JSON**——日志系统能直接按
 * {@code decisionId} 检索，人也能在终端里一眼读完。热路径的开销是一次字符串拼接，
 * <b>不做序列化框架调用</b>；换 ObjectMapper 会把反射与树构建搬进决策热路径，
 * 换来的只是这里的几十行拼装。
 *
 * <p><b>引号与转义只有一处实现</b>（{@link #quoted} / {@link #quoteOrNull} / {@link #escape}）。
 * 原先它是散的：{@code hitActivityId} 的引号在<b>实参</b>里、而 {@code scene}/{@code strategy}/
 * {@code mode} 的引号在<b>模板</b>里，转义则是就地一个 {@code replace("\"", "'")}。
 * 那个分裂不是随手写的——引号在实参里才能让 null 输出成<b>裸 {@code null}</b> 而不是字符串
 * {@code "null"}，这个区别对下游解析是实打实的。收敛后由两个方法把这层语义显式化：
 * {@link #quoteOrNull} 保留裸 null，{@link #quoted} 恒带引号（用于本就不可为 null 的字段，
 * 输出与改造前逐字节一致）。
 */
@Component
public class DecisionAuditor {

    /**
     * 决策审计日志。**独立 logger 名**，好让日志采集按名字单独路由到长保留期的索引——
     * 与业务运行日志混在一起时，要么审计跟着 DEBUG 一起被丢掉，要么运行日志跟着审计一起长期留存。
     */
    private static final Logger audit = LoggerFactory.getLogger("activity.decision.audit");

    // ------------------------------------------------------------------ 红包 / 折扣

    /**
     * 红包通道留痕。字段顺序与取值与改造前逐字节一致——它已经是日志系统里的检索契约。
     *
     * <p>{@code source}/{@code generation} 必须一起落：只有 {@code hitVersion}（活动版本）
     * 而没有代际时，「活动版本对、但快照是旧代」这类事故在日志里查不出来，
     * 而它恰恰是客服最难缠的那类工单。
     */
    public void discount(DecisionScene scene, SpuDiscountRequest req, DiscountView v) {
        if (v == null || !audit.isInfoEnabled()) return;
        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < v.items().size(); i++) {
            DiscountItem it = v.items().get(i);
            if (i > 0) items.append(',');
            items.append("{\"activityId\":").append(quoted(it.activityId()))
                 .append(",\"version\":").append(it.version())
                 .append(",\"form\":").append(quoted(it.benefitForm()))
                 .append(",\"amount\":").append(it.amount())
                 .append(",\"applied\":").append(it.applied())
                 .append(",\"reject\":").append(quoteOrNull(it.rejectReason()))
                 .append('}');
        }
        items.append(']');
        DecisionProvenance p = v.provenance() == null ? DecisionProvenance.db() : v.provenance();
        audit.info("{\"decisionId\":{},\"scene\":{},\"userId\":{},\"spuIds\":{},"
                        + "\"orderAmount\":{},\"hit\":{},\"hitActivityId\":{},\"hitVersion\":{},"
                        + "\"amount\":{},\"strategy\":{},\"clamped\":{},\"mode\":{},"
                        + "\"source\":{},\"generation\":{},\"items\":{}}",
                quoted(v.decisionId()), quoted(code(scene)), req.userId(), req.spuIdList(),
                req.orderAmount(), v.hit(), quoteOrNull(v.hitActivityId()),
                v.hitVersion(), v.hitAmount(), quoted(v.strategy()), v.clamped(), quoted(v.mode()),
                quoted(p.source()), p.generation(), items);
    }

    // ------------------------------------------------------------------ 买赠

    /**
     * 买赠通道留痕。
     *
     * <p>买赠<b>没有单一赢家</b>，所以这里没有 {@code hitActivityId}——逐件赠品各自带来源活动
     * 与版本（{@code GiftResult.activityId/version}），「这件赠品当时按哪个活动发的」
     * 只能从这个数组里回答。
     */
    public void gifts(DecisionScene scene, SpuDiscountRequest req, GiftView v) {
        if (v == null || !audit.isInfoEnabled()) return;
        StringBuilder gifts = new StringBuilder("[");
        for (int i = 0; i < v.gifts().size(); i++) {
            GiftResult g = v.gifts().get(i);
            if (i > 0) gifts.append(',');
            gifts.append("{\"activityId\":").append(quoteOrNull(g.getActivityId()))
                 .append(",\"version\":").append(g.getVersion())
                 .append(",\"giftName\":").append(quoteOrNull(g.getGiftName()))
                 .append(",\"giftType\":").append(quoteOrNull(g.getGiftType()))
                 .append(",\"giftNum\":").append(g.getGiftNum())
                 .append(",\"amount\":").append(g.getAbsoluteAmount())
                 .append('}');
        }
        gifts.append(']');
        DecisionProvenance p = v.provenance() == null ? DecisionProvenance.db() : v.provenance();
        audit.info("{\"decisionId\":{},\"scene\":{},\"userId\":{},\"spuIds\":{},"
                        + "\"orderAmount\":{},\"giftCount\":{},\"mode\":{},"
                        + "\"source\":{},\"generation\":{},\"gifts\":{}}",
                quoted(v.decisionId()), quoted(code(scene)), req.userId(), req.spuIdList(),
                req.orderAmount(), v.gifts().size(), quoted(v.mode()),
                quoted(p.source()), p.generation(), gifts);
    }

    // ------------------------------------------------------------------ 加价购（两阶段）

    /** 加价购第一阶段留痕：这一单列出了哪些换购选项、各加多少钱。 */
    public void addOnOptions(DecisionScene scene, SpuDiscountRequest req, AddOnOptions o) {
        if (o == null || !audit.isInfoEnabled()) return;
        StringBuilder options = new StringBuilder("[");
        for (int i = 0; i < o.options().size(); i++) {
            AddOnOption op = o.options().get(i);
            if (i > 0) options.append(',');
            options.append("{\"activityId\":").append(quoteOrNull(op.activityId()))
                   .append(",\"version\":").append(op.version())
                   .append(",\"itemName\":").append(quoteOrNull(op.itemName()))
                   .append(",\"addOnPrice\":").append(op.addOnPrice())
                   .append('}');
        }
        options.append(']');
        audit.info("{\"decisionId\":{},\"scene\":{},\"phase\":{},\"userId\":{},\"spuIds\":{},"
                        + "\"orderAmount\":{},\"optionCount\":{},"
                        + "\"source\":{},\"generation\":{},\"options\":{}}",
                quoted(o.decisionId()), quoted(code(scene)), quoted(AddOnPurchaseService.PHASE_OPTIONS),
                req.userId(), req.spuIdList(), req.orderAmount(), o.options().size(),
                quoteOrNull(source(o.provenance())), generation(o.provenance()), options);
    }

    /**
     * 加价购第二阶段留痕：按「活动+换购品」重查后的权威报价，或拒绝原因。
     *
     * <p>{@code source} 可能是<b>裸 null</b>——参数校验拒绝的那条路径根本没装载过物料，
     * 而「没查过」与「查了库」是两件不同的事，不能用 {@code db} 顶替。
     */
    public void addOnQuote(DecisionScene scene, SpuDiscountRequest req, AddOnQuote q) {
        if (q == null || !audit.isInfoEnabled()) return;
        audit.info("{\"decisionId\":{},\"scene\":{},\"phase\":{},\"userId\":{},\"spuIds\":{},"
                        + "\"orderAmount\":{},\"ok\":{},\"activityId\":{},\"itemName\":{},"
                        + "\"addOnPrice\":{},\"reason\":{},\"source\":{},\"generation\":{}}",
                quoted(q.decisionId()), quoted(code(scene)), quoted(AddOnPurchaseService.PHASE_QUOTE),
                req.userId(), req.spuIdList(), req.orderAmount(), q.ok(),
                quoteOrNull(q.activityId()), quoteOrNull(q.itemName()), q.addOnPrice(),
                quoteOrNull(q.reason()), quoteOrNull(source(q.provenance())), generation(q.provenance()));
    }

    // ------------------------------------------------------------------ 引号 / 转义（唯一实现）

    /**
     * 恒带引号。给<b>本就不可为 null</b> 的字段用（scene / strategy / mode / source / decisionId）：
     * null 会输出成字符串 {@code "null"}，这与改造前把引号写在日志模板里的行为逐字节一致。
     */
    static String quoted(String s) {
        return '"' + escape(String.valueOf(s)) + '"';
    }

    /**
     * null 输出成<b>裸 {@code null}</b>，非 null 才加引号。
     *
     * <p>这正是改造前 {@code hitActivityId} 把引号写在实参里的原因——下游解析
     * {@code "hitActivityId":null}（没命中）与 {@code "hitActivityId":"null"}（有个叫 null 的活动）
     * 是两回事。别把它和 {@link #quoted} 合并成一个方法。
     */
    static String quoteOrNull(String s) {
        return s == null ? "null" : '"' + escape(s) + '"';
    }

    /**
     * JSON 字符串转义。**覆盖反斜杠与控制字符**，不只是双引号——
     * 改造前这里是就地一个 {@code replace("\"", "'")}：活动名里出现一个反斜杠或换行
     * （运营从表格里粘贴过来是常态）就会产出<b>不可解析的一行</b>，
     * 而症状是日志采集侧静默丢弃这条审计，恰好是最需要它的时候。
     */
    static String escape(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String rep = switch (c) {
                case '"' -> "\\\"";
                case '\\' -> "\\\\";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                case '\b' -> "\\b";
                case '\f' -> "\\f";
                default -> c < 0x20 ? String.format("\\u%04x", (int) c) : null;
            };
            if (rep == null) {
                if (sb != null) sb.append(c);
                continue;
            }
            if (sb == null) sb = new StringBuilder(s.length() + 8).append(s, 0, i);
            sb.append(rep);
        }
        return sb == null ? s : sb.toString();
    }

    private static String code(DecisionScene scene) {
        return scene == null ? null : scene.code();
    }

    private static String source(DecisionProvenance p) {
        return p == null ? null : p.source();
    }

    private static Long generation(DecisionProvenance p) {
        return p == null ? null : p.generation();
    }
}
