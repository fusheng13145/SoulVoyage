package com.leyon.soul.orchestrator;

import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.common.util.Ulid;
import com.leyon.soul.domain.task.TaskInstanceEntity;
import com.leyon.soul.domain.task.TaskInstanceRepository;
import com.leyon.soul.domain.user.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务创建闸门（2026-11-20 并发审查修复）。
 *
 * <p>原实现把 {@code countActive(userId) >= 2} 与 {@code save} 分两步做在无事务的 submit 里，
 * 实测 60 路并发 POST /tasks 有 4 个拿到 202（tasks total 2→6），即"单用户 2 个活跃任务"可被并发穿透。
 * 现在把这段收进一个事务，并在计数前对该用户加悲观写锁（{@code UserRepository#lockById}）：
 * 同用户第 2 个请求会等第 1 个提交后再计数。跨用户互不阻塞。
 *
 * <p>为什么单独成类：submit 里还有事件发布与领域钩子（写日记行）等副作用，不该被塞进这个短事务，
 * 否则提交时间被外部副作用拉长、锁持有时间也随之变长。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskGateService {

    /** 单用户活跃任务上限（产品约束，与错误码 3003 一致） */
    static final int MAX_ACTIVE_TASKS = 2;

    private final TaskInstanceRepository taskRepo;
    private final UserRepository userRepo;
    private final MeterRegistry registry;

    @Transactional
    public TaskInstanceEntity create(long userId, PipelineCode code, String clientReqId) {
        userRepo.lockById(userId);   // 必须在事务内：锁随事务提交释放
        if (taskRepo.countActive(userId) >= MAX_ACTIVE_TASKS) {
            throw new BizException(ErrorCode.TASK_CONCURRENCY_LIMIT);
        }
        TaskInstanceEntity t = new TaskInstanceEntity();
        t.setTaskNo(Ulid.next());
        t.setUserId(userId);
        t.setPipelineCode(code.name());
        t.setClientReqId(clientReqId);
        TaskInstanceEntity saved = taskRepo.save(t);
        registry.counter("sv.task.submit", "pipeline", code.name()).increment();
        return saved;
    }
}
