package com.lrj.drools.domain;

/**
 * Step 14: 失控循环演示用的可变计数器 fact。
 *
 * 规则 "Runaway increment" 看 value、改 value, 再激活自己 → 无限循环。
 * 故意做成 mutable POJO (跟 Step 3 的 Cart、Step 12 的 Sensor 同理):
 * 只有可变字段被 modify 才能让规则反复重新激活, 把"失控"演出来。
 *
 * 它的存在是给两个护栏当靶子:
 *   - fireAllRules(maxFires) 硬上限截断
 *   - 另一线程 session.halt() 超时打断
 */
public class Counter {

    private int value;

    public Counter(int value) {
        this.value = value;
    }

    public int getValue() { return value; }
    public void setValue(int value) { this.value = value; }
}
