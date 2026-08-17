package com.lrj.drools.domain;

import org.drools.base.factmodel.traits.Traitable;

/**
 * Step 21: traits 的核心 fact（core object）。
 *
 * `@Traitable`（org.drools.base.factmodel.traits）把这个 POJO 标成"可以在运行时被动态贴接口"。
 * 引擎会给它加一个隐藏的动态属性表：`don($a, SomeTrait.class)` 时，trait 里**核心类没有的字段**
 * （比如 tier / creditLimit）就存进这张表，核心类已有的字段（name）则直接映射。
 *
 * traits = 给 fact 动态贴一层"接口实现"做多态：don 之后这个 applicant 同时也是 PremiumApplicant，
 * 能被 `PremiumApplicant(...)` 模式匹配到；shed 则把这层摘掉。跟普通继承的区别是**运行时可加可减**。
 *
 * 必须是 mutable POJO（带 getter/setter），trait 代理要能读写核心字段。
 */
@Traitable
public class Applicant {

    private String name;
    private int age;
    private long annualIncome;

    public Applicant() {
    }

    public Applicant(String name, int age, long annualIncome) {
        this.name = name;
        this.age = age;
        this.annualIncome = annualIncome;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getAge() { return age; }
    public void setAge(int age) { this.age = age; }
    public long getAnnualIncome() { return annualIncome; }
    public void setAnnualIncome(long annualIncome) { this.annualIncome = annualIncome; }
}
