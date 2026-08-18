package com.lrj.drools.domain;

/**
 * Step 12: TMS (Truth Maintenance System) 展示用的传感器 fact。
 *
 * 设计动机:
 *   - mutable POJO（不是 record），因为规则能力需要 `modify($sensor) { setValue(...) }`
 *     来推进"前提变化"。record 不可变, modify 不出来。
 *   - LHS 看 value 字段; 规则在 value 超阈值时衍生出一个 Alert fact。
 *     Step 12 的核心对比:
 *       a) `insertLogical(new Alert(...))` — 衍生 fact 跟"value > 阈值"这个前提绑定,
 *          value 一掉下来, Alert 自动被引擎 retract
 *       b) `insert(new Alert(...))`        — 普通 insert, value 变了 Alert 也不会消失,
 *          要手动 retract 才能撤销
 *
 *   这是 Drools 区别于普通 if/else 引擎的核心特性: TMS 把"前提-结论"的因果链交给
 *   引擎维护, 业务规则只描述"什么情况下应当有 Alert", 不用关心"什么情况下应当撤销 Alert"。
 */
public class Sensor {

    private final String name;
    private double value;

    public Sensor(String name, double value) {
        this.name = name;
        this.value = value;
    }

    public String getName() { return name; }
    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }
}
