package com.leyon.soul.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.domain.crisis.CrisisState;
import com.leyon.soul.domain.task.TaskInstanceEntity;
import com.leyon.soul.domain.task.TaskInstanceRepository;
import com.leyon.soul.domain.task.TaskStepLogEntity;
import com.leyon.soul.domain.task.TaskStepLogRepository;
import com.leyon.soul.domain.user.UserRepository;
import com.leyon.soul.orchestrator.agent.OutputInvalidException;
import com.leyon.soul.orchestrator.pipeline.PipelineRegistry;
import com.leyon.soul.orchestrator.pipeline.StepSpec;
import com.leyon.soul.orchestrator.pipeline.TaskContext;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 任务执行服务（B-03）：在虚拟线程中运行，不持有数据库事务，逐步提交。
 * 负责危机上下文、步骤推进、文本穿透、降级收口、重跑续接、取消与终态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutionService {

    private final PipelineRegistry pipelines;
    private final TaskInstanceRepository taskRepo;
    private final TaskStepLogRepository stepRepo;
    private final UserRepository userRepo;
    private final MeterRegistry registry;
    private final TaskStepRunner stepRunner;
    private final TaskEventPublisher events;
    private final TaskDispatcher dispatcher;
    private final TaskMessageCodec codec;
    private final ObjectMapper mapper;

    public void run(long taskId, JsonNode input) {
        run(taskId, input, 1, null);
    }

    /** A1 手动重跑：FAILED 任务从首个失败步重跑，此前步骤复用已落库的 MIDDLE_RESULT（append-only 语义不破坏） */
    public TaskInstanceEntity retry(TaskInstanceEntity t) {
        if (!"FAILED".equals(t.getStatus())) {
            throw new BizException(ErrorCode.BAD_PARAMS, "仅失败任务可重跑");
        }
        int startSeq = stepRepo.findByTaskIdOrderByStepSeqAscIdAsc(t.getId()).stream()
                .filter(s -> "FAILED".equals(s.getStatus()))
                .mapToInt(TaskStepLogEntity::getStepSeq).min().orElse(1);
        JsonNode input = codec.firstRequest(t);
        if (input == null) throw new BizException(ErrorCode.BAD_PARAMS, "任务缺少入参记录，无法重跑");
        JsonNode carryPrev = startSeq > 1 ? codec.middleResult(t, startSeq - 1) : null;
        dispatcher.dispatch(() -> run(t.getId(), input, startSeq, carryPrev));
        return t;
    }

    public void run(long taskId, JsonNode input, int startSeq, JsonNode carryPrev) {
        TaskInstanceEntity t = taskRepo.findById(taskId).orElseThrow();
        if (TaskStatus.of(t.getStatus()) == TaskStatus.CANCELLED) return;
        try {
            TaskStateMachine.requireTransition(TaskStatus.of(t.getStatus()), TaskStatus.RUNNING);
            t.setStatus(TaskStatus.RUNNING.name());
            t.setStartedAt(Instant.now());
            taskRepo.save(t);
            events.status(t.getTaskNo(), TaskStatus.RUNNING);

            CrisisState crisisState = userRepo.findById(t.getUserId())
                    .map(u -> CrisisState.parse(u.getCrisisState())).orElse(CrisisState.NORMAL);
            TaskContext ctx = new TaskContext(taskId, t.getUserId(), crisisState, input);
            var steps = pipelines.require(t.getPipelineCode()).steps(ctx);

            JsonNode last = null;
            boolean degraded = false;
            int seq = 0;
            JsonNode base = input;
            if (startSeq > 1) {
                // 重跑续接：失败步之前的产出直接复用，文本提升按原顺序重放
                last = carryPrev;
                for (int s = 1; s < startSeq; s++) {
                    JsonNode out = codec.middleResult(t, s);
                    if (out != null) base = promoteDiaryText(base, out);
                }
            }
            for (StepSpec spec : steps) {
                seq++;
                if (isCancelled(taskId)) return;
                if (seq < startSeq) continue;
                try {
                    last = stepRunner.execute(t, spec, seq, base, last);
                    // 文本源随上下文穿透：上游步产出 diaryText（如 COMPANION digest）时提升为顶层，
                    // 使 EMOTION/TRACE/RISK_ARCHIVE 按既有契约消费多步流水线（DIARY 等顶层已有则不变）
                    base = promoteDiaryText(base, last);
                } catch (OutputInvalidException e) {
                    // 校验失败不重试：步骤降级，流水线终止为 PARTIAL_SUCCESS（手册 §3.6）
                    stepRunner.degrade(t, spec, seq, e.getMessage());
                    degraded = true;
                    events.stepFailed(t.getTaskNo(), seq, spec.agentCode(), e.getMessage());
                    break;
                }
            }

            if (last != null) {
                stepRunner.persistFinal(t, steps.size() + 1, last);
            }
            TaskStatus finalStatus = degraded ? TaskStatus.PARTIAL_SUCCESS : TaskStatus.SUCCESS;
            finish(t, finalStatus, null);
            events.done(t.getTaskNo(), finalStatus, last == null ? mapper.nullNode() : last);
        } catch (Exception e) {
            log.error("task {} failed", t.getTaskNo(), e);
            if (TaskStateMachine.canTransit(TaskStatus.of(t.getStatus()), TaskStatus.FAILED)) {
                finish(t, TaskStatus.FAILED, abbreviate(e.getMessage()));
            }
            events.error(t.getTaskNo());
        }
    }

    public void cancel(TaskInstanceEntity t) {
        TaskStatus status = TaskStatus.of(t.getStatus());
        if (status == TaskStatus.SUCCESS || status == TaskStatus.FAILED) {
            throw new BizException(ErrorCode.BAD_PARAMS, "任务已结束，无法取消");
        }
        finish(t, TaskStatus.CANCELLED, null);
        events.done(t.getTaskNo(), TaskStatus.CANCELLED);
    }

    private boolean isCancelled(long taskId) {
        return taskRepo.findById(taskId).map(x -> "CANCELLED".equals(x.getStatus())).orElse(true);
    }

    private void finish(TaskInstanceEntity t, TaskStatus status, String err) {
        TaskStateMachine.requireTransition(TaskStatus.of(t.getStatus()), status);
        t.setStatus(status.name());
        t.setErrorMsg(err);
        t.setFinishedAt(Instant.now());
        taskRepo.save(t);
        if (t.getStartedAt() != null) {
            registry.timer("sv.task.duration", "pipeline", t.getPipelineCode(), "status", status.name())
                    .record(Duration.between(t.getStartedAt(), t.getFinishedAt()));
        }
        events.status(t.getTaskNo(), status);
    }

    private JsonNode promoteDiaryText(JsonNode base, JsonNode out) {
        if (out == null || !out.hasNonNull("diaryText") || base.hasNonNull("diaryText")) return base;
        base = base.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) base).set("diaryText", out.get("diaryText"));
        return base;
    }

    private static String abbreviate(String s) {
        return s == null ? null : s.substring(0, Math.min(500, s.length()));
    }
}
