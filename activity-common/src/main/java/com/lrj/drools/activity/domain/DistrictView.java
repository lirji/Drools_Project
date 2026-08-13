package com.lrj.drools.activity.domain;

import com.lrj.drools.activity.persistence.DistrictEntity;

/**
 * 行政区划字典的**对外形状**。控制台地域选择器的取值域。
 *
 * <p><b>为什么不直接返回 {@link DistrictEntity}</b>：`/list` 返回裸实体正是本仓记载过的响应键序事故来源
 * （见 {@code docs/architecture.md} §7 那段 {@code @JsonPropertyOrder} 注记）。
 * 而且实体 11 列里有 4 列前端拿到没有用途——{@code provinceCode}/{@code cityCode}/{@code fullName}
 * 都能由 {@code parent} 链推出，{@code sortNo} 已经体现为**数组顺序**（服务端固定按它排序）。
 * 把 sortNo 传出去等于让前端有机会二次排序，把服务端的权威顺序变成一个可选项。
 *
 * <p><b>{@code pinyin} / {@code pinyinInitial} 归一化成非空串</b>（随包数据里有 2 行缺拼音、5 行缺首字母）：
 * 前端搜索是 {@code d.pinyin.includes(q)}，可空会在那几行上炸。归一化在服务端做一次，
 * 好过让每个消费方各自记得判空。{@code pinyin} 同时**去掉空格**（库里是空格分词的「nan shan」），
 * 让「nanshan」这种连写也能命中。
 *
 * @param code          6 位行政区划代码，就是 {@code userDistrictId} / {@code district_ids} 携带的值
 * @param name          全称，如「南山区」
 * @param shortName     简称，如「南山」。列表更短、搜索更容易命中
 * @param level         1=省级 2=地市级 3=区县级。<b>与树深解耦</b>：117 个区县级直接挂省级，仍是 3
 * @param parent        上级代码；省级为 {@code null}
 * @param pinyin        全拼，已去空格
 * @param pinyinInitial 拼音首字母；给不出字母时为空串
 */
public record DistrictView(String code, String name, String shortName, int level,
                           String parent, String pinyin, String pinyinInitial) {

    public static DistrictView from(DistrictEntity e) {
        return new DistrictView(
                e.getCode(),
                e.getName(),
                e.getShortName(),
                e.getDistrictLevel() == null ? 3 : e.getDistrictLevel(),
                e.getParentCode(),
                e.getPinyin() == null ? "" : e.getPinyin().replace(" ", ""),
                initialsOf(e.getPinyin(), e.getPinyinInitial()));
    }

    /**
     * 逐字取首字母：「guang dong」→「gd」。
     *
     * <p><b>不能直接用库里的 {@code pinyin_initial} 列</b>：那一列存的是整串的第一个字母
     * （广东省 = {@code g}、两江新区 = {@code l}），全表 3207 行**都只有 1 个字符**。
     * 而前端的首字母分支是 {@code t.length > 1 && pinyinInitial.startsWith(t)}——
     * 拿 1 个字符去 {@code startsWith("gd")} 恒为假，那条分支**从来没有命中过一次**，
     * 「支持首字母搜索」是句空话（单字符查询由全拼前缀兜住，所以一直没人发现）。
     *
     * <p>逐字首字母只能从**带空格**的原始拼音推——{@code pinyin} 出参已经去过空格，
     * 词边界在那一步就丢了，所以这里读的是 {@code e.getPinyin()} 而不是出参。
     * 拼音缺失的那几行回落到库里那一列，仍保证非 null。
     */
    private static String initialsOf(String spacedPinyin, String stored) {
        if (spacedPinyin != null && !spacedPinyin.isBlank()) {
            StringBuilder sb = new StringBuilder();
            for (String segment : spacedPinyin.trim().split("\\s+")) {
                if (!segment.isEmpty()) sb.append(segment.charAt(0));
            }
            if (sb.length() > 0) return sb.toString();
        }
        return stored == null ? "" : stored;
    }
}
