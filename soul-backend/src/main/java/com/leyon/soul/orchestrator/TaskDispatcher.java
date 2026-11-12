package com.leyon.soul.orchestrator;

import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 任务派发器：虚拟线程执行器，提交/重跑共用（原 OrchestratorService 内联字段）。 */
@Component
public class TaskDispatcher {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public void dispatch(Runnable task) {
        executor.submit(task);
    }
}
