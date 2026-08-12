package com.lrj.drools.activity.error;

/**
 * 活动平台的<b>错误分类</b>——「这是哪一类失败」与「它对应哪个 HTTP 状态」的**唯一**定义处。
 *
 * <p>此前这张映射表根本不存在：全仓 {@code @ControllerAdvice} / {@code @ExceptionHandler} 零命中，
 * 唯一的映射是 {@code ActivityMarketingController} 里手抄三遍的
 * {@code catch (IllegalArgumentException) → 400 / catch (IllegalStateException) → 409}。
 * 于是「分类」被压缩成了 JDK 两个通用异常类型，凡是塞不进这两格的语义都会被强行归错格——
 * 四眼校验失败（<b>不该由这个人做这件事</b>）就是这么变成 409「冲突」的。
 *
 * <p><b>每个取值都有生产抛出点</b>。刻意<b>不</b>先把整张理想中的错误码表铺开：
 * 一个没人抛的错误码与文档里写着却没人调用的回滚入口是同一类东西——看着完备，用起来是空的。
 * 新增取值时请连同抛出点一起加。
 *
 * <p>已知的两个<b>有意留空</b>的格子，别当成遗漏：
 * <ul>
 *   <li><b>「活动不存在」</b>：语义上是 404，但今天它走
 *       {@code IllegalArgumentException} → controller catch → <b>400</b>。改成 404 是一次
 *       面向调用方的状态码变更（前端 / 脚本 / e2e 都会看到），不属于本次「异常分类」重构的授权范围，
 *       要单独立项。</li>
 *   <li><b>「权益形态配置非法」</b>：它今天就是 400，换成专属错误码不改变任何 HTTP 行为，
 *       只会把 {@code create} 校验链拆成「一半抛领域异常、一半抛 IAE」的混合体——
 *       要做就整条校验链一起做。在此之前，这类失败由 {@link #INVALID_ARGUMENT} 统一承载。</li>
 * </ul>
 */
public enum ActivityErrorCode {

    /** 入参非法（校验不通过）。对应改造前的 {@code IllegalArgumentException → 400}。 */
    INVALID_ARGUMENT(400),

    /**
     * 四眼职责分离拒绝：审批人缺失或审批人 == 提交人。
     *
     * <p><b>403 而不是 409</b>——这是本次唯一有意的状态码修正。它说的是「<b>不该由你来做</b>」，
     * 不是「资源状态和你以为的不一样，重试可能会成」。改造前它落在 409 上，
     * 调用方拿到 409 的标准反应是<b>重试</b>，而这里再怎么重试也永远不会成功：
     * 必须换一个人来点。状态码选错时，客户端的正确行为也就跟着写错了。
     */
    FOUR_EYES_REQUIRED(403),

    /** 版本冲突（并发编辑）。 */
    VERSION_CONFLICT(409),

    /** 同租户同 requestId 的并发重复提交（由唯一约束兜底识别）。 */
    DUPLICATE_REQUEST(409),

    /** 其它状态冲突。对应改造前的 {@code IllegalStateException → 409}。 */
    STATE_CONFLICT(409),

    /**
     * 未预期的内部错误。<b>只在决策平面使用</b>，且**不回显异常 message**：
     * decision 是只读热路径，它抛出的 IAE 只可能来自脏数据或真 bug，回显内部细节没有收益、只有信息泄漏。
     */
    INTERNAL(500);

    private final int httpStatus;

    ActivityErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    /** 该分类对应的 HTTP 状态码。 */
    public int httpStatus() {
        return httpStatus;
    }
}
