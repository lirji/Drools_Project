package com.lrj.drools.domain;

/**
 * Step 22: fireUntilHalt 的输入事实——一个待处理任务。
 *
 * fireUntilHalt 让引擎跑在一个守护线程里持续 fire，主线程把 Task 一个个 insert 进来被实时处理，
 * 直到显式 halt。约定 id == "__STOP__" 的是**哨兵任务**：处理到它时规则调 `drools.halt()` 收尾
 * （见 fire-until-halt.drl），省得跨线程精确掐 halt 时机。
 */
public record Task(String id, int amount) {

    /** 哨兵任务 id：处理到它时让 fireUntilHalt 停下。 */
    public static final String STOP = "__STOP__";
}
