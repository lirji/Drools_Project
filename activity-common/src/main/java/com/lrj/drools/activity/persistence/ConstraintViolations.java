package com.lrj.drools.activity.persistence;

import java.util.Locale;

/**
 * 「这次完整性错误是不是<b>某一条具名约束</b>炸的」——把这个判断从异常文案里救出来。
 *
 * <p><b>它替换掉了什么</b>：改造前 {@code ActivityMarketingService.saveManage} 里是
 * {@code e.getMessage().toLowerCase().contains("uk_am_tenant_request")}——把异常 message 当成了
 * 控制流的 key。那串文案不是我们写的：它由 Hibernate 方言 + JDBC 驱动 + 数据库版本共同拼出来，
 * 换个驱动小版本就可能变形。而这段判断的两条出路是「409 让客户端重试」与「500 原样上抛」，
 * 判错的代价是一次并发重复提交被报成内部错误（或反过来，一条真正的完整性 bug 被伪装成"重复请求"）。
 *
 * <p>现在的判据顺序：
 * <ol>
 *   <li>沿 cause 链找 {@link org.hibernate.exception.ConstraintViolationException}，读它<b>解析好的</b>
 *       约束名。H2 会把约束名带上索引后缀（{@code UK_AM_TENANT_REQUEST_INDEX_5}），
 *       故用 contains 而不是 equals。</li>
 *   <li>解析不出约束名时（部分方言/驱动组合下 Hibernate 给不出）<b>回落到原来的 message 匹配</b>——
 *       它从"唯一判据"降级成"兜底"，不再是这条路径成立的前提。</li>
 * </ol>
 *
 * <p>约束名本身由实体常量提供（如 {@link ActivityManageEntity#UK_TENANT_REQUEST}），
 * 与 {@code @UniqueConstraint(name=...)} 是<b>同一个</b>字面量，改名时编译器跟着走。
 */
public final class ConstraintViolations {

    private ConstraintViolations() {}

    /** cause 链最多走这么深——防御环形 cause（自引用已单独判，这里防 A→B→A）。 */
    private static final int MAX_DEPTH = 16;

    public static boolean isViolationOf(Throwable ex, String constraintName) {
        if (ex == null || constraintName == null) return false;
        String want = constraintName.toLowerCase(Locale.ROOT);

        Throwable t = ex;
        for (int depth = 0; t != null && depth < MAX_DEPTH; depth++) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                if (name != null && name.toLowerCase(Locale.ROOT).contains(want)) return true;
            }
            Throwable cause = t.getCause();
            if (cause == t) break;
            t = cause;
        }

        String msg = ex.getMessage();
        return msg != null && msg.toLowerCase(Locale.ROOT).contains(want);
    }
}
