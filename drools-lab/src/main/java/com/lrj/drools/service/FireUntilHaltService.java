package com.lrj.drools.service;

import com.lrj.drools.domain.ProcessedTask;
import com.lrj.drools.domain.Task;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 22: fireUntilHalt 运行入口。
 *
 * 跟其它 Step "insert → fireAllRules → dispose" 的最大不同：这里 fire 跑在**另一个线程**里，
 * 是个不返回的循环。编排：
 *   1. 起线程 T 跑 session.fireUntilHalt()（阻塞，等事实）
 *   2. 主线程把 Task 一个个 insert 进去，被 T 实时处理
 *   3. 最后 insert 一个哨兵 Task("__STOP__")，规则 RHS 调 drools.halt() 让 T 退出
 *   4. join(T) 等它跑完，再从 working memory 捞 ProcessedTask、dispose
 *
 * 为什么不直接主线程 halt：fireUntilHalt 异步消费，主线程调 halt 可能在任务还没处理完时就打断。
 * 用"哨兵任务 + 低 salience halt 规则"让引擎自己在处理完所有任务后收尾，确定性更好。
 */
@Service
public class FireUntilHaltService {

    private final KieContainer kieContainer;

    public FireUntilHaltService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    public Result process(List<Task> tasks) throws InterruptedException {
        KieSession session = kieContainer.newKieSession("fireUntilHaltSession");
        try {
            // 1. 守护线程里跑 fireUntilHalt（阻塞循环，等事实到来）
            Thread fireThread = new Thread(session::fireUntilHalt, "fireUntilHalt-worker");
            fireThread.setDaemon(true);
            fireThread.start();

            // 2. 主线程逐个 insert 任务，被 fire 线程实时消费
            for (Task t : tasks) {
                session.insert(t);
            }
            // 3. 哨兵：处理到它时规则调 drools.halt() 收尾
            session.insert(new Task(Task.STOP, 0));

            // 4. 等 fire 线程因 halt 返回
            fireThread.join(5000);

            List<ProcessedTask> processed = new ArrayList<>();
            for (Object obj : session.getObjects()) {
                if (obj instanceof ProcessedTask p) {
                    processed.add(p);
                }
            }
            return new Result(processed, processed.size());
        } finally {
            session.dispose();
        }
    }

    public record Result(List<ProcessedTask> processed, int processedCount) {}
}
