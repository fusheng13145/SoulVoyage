package com.soulvoyage.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.util.Ulid;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.crisis.CrisisState;
import com.soulvoyage.domain.task.*;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.llm.LlmUsageCollector;
import io.micrometer.core.instrument.MeterRegistry;
import com.soulvoyage.orchestrator.agent.AgentRuntime;
import com.soulvoyage.orchestrator.agent.LlmUnavailableException;
import com.soulvoyage.orchestrator.agent.OutputInvalidException;
import com.soulvoyage.orchestrator.pipeline.PipelineRegistry;
import com.soulvoyage.orchestrator.pipeline.StepSpec;
import com.soulvoyage.orchestrator.pipeline.TaskContext;
import com.soulvoyage.orchestrator.protocol.AgentMessage;
import com.soulvoyage.orchestrator.sse.TaskEventBus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 调度中心（手册 §3）：任务生命周期唯一管控者。
 * Agent 之间不直接互调；所有中间结果经此中转、Schema 校验、加密落库（append-only）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestratorService {

    private static final int MAX_ATTEMPTS = 3;          // 首次 + 2 次重试（仅可重试异常）
    private static final long[] BACKOFF_MS = {2000, 8000};

    private final PipelineRegistry pipelines;
    private final AgentRegistry agents;
    private final TaskInstanceRepository taskRepo;
    private final TaskStepLogRepository stepRepo;
    private final AgentMessageRepository msgRepo;
    private final UserRepository userRepo;
    private final CryptoService crypto;
    private final TaskEventBus bus;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 提交任务：幂等 + 并发上限（单用户 2 个活跃任务） */
    public TaskInstanceEntity submit(long userId, String pipelineCode, JsonNode input, String clientReqId) {
        pipelines.require(pipelineCode);   // 校验流水线存在

        if (clientReqId != null && !clientReqId.isBlank()) {
            var exist = taskRepo.findByUserIdAndClientReqId(userId, clientReqId);
            if (exist.isPresent()) return exist.get();
        }
        if (taskRepo.countActive(userId) >= 2) {
            throw new BizException(ErrorCode.TASK_CONCURRENCY_LIMIT);
        }

        TaskInstanceEntity t = new TaskInstanceEntity();
        t.setTaskNo(Ulid.next());
        t.setUserId(userId);
        t.setPipelineCode(pipelineCode);
        t.setClientReqId(blankToNull(clientReqId));
        final TaskInstanceEntity saved = taskRepo.save(t);
        registry.counter("sv.task.submit", "pipeline", pipelineCode).increment();

        bus.publish(saved.getTaskNo(), "task_created", Map.of("taskNo", saved.getTaskNo(), "status", saved.getStatus()));
        JsonNode inputCopy = input == null ? mapper.createObjectNode() : input;
        executor.submit(() -> run(saved.getId(), inputCopy));
        return saved;
    }

    /** 执行循环：在虚拟线程中运行，不持有数据库事务，逐步提交 */
    private void run(long taskId, JsonNode input) {
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
        // 原始输入 = 首步 REQUEST 消息（executeStep 在无上游产出时即按原输入落库）
        JsonNode input = msgRepo.findByTaskIdOrderByStepSeqAscIdAsc(t.getId()).stream()
                .filter(m -> m.getStepSeq() == 1 && "REQUEST".equals(m.getMsgType()))
                .findFirst()
                .map(m -> decrypt(t.getUserId(), m))
                .orElseThrow(() -> new BizException(ErrorCode.BAD_PARAMS, "任务缺少入参记录，无法重跑"));
        JsonNode carryPrev = startSeq > 1 ? middleResult(t, startSeq - 1) : null;
        executor.submit(() -> run(t.getId(), input, startSeq, carryPrev));
        return t;
    }

    private void run(long taskId, JsonNode input, int startSeq, JsonNode carryPrev) {
        TaskInstanceEntity t = taskRepo.findById(taskId).orElseThrow();
        try {
            t.setStatus("RUNNING");
            t.setStartedAt(Instant.now());
            taskRepo.save(t);
            bus.publish(t.getTaskNo(), "task_status", Map.of("status", "RUNNING"));

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
                    JsonNode out = middleResult(t, s);
                    if (out != null) base = promoteDiaryText(base, out);
                }
            }
            for (StepSpec spec : steps) {
                seq++;
                if (isCancelled(taskId)) return;
                if (seq < startSeq) continue;
                try {
                    last = executeStep(t, spec, seq, base, last);
                    // 文本源随上下文穿透：上游步产出 diaryText（如 COMPANION digest）时提升为顶层，
                    // 使 EMOTION/TRACE/RISK_ARCHIVE 按既有契约消费多步流水线（DIARY 等顶层已有则不变）
                    base = promoteDiaryText(base, last);
                } catch (OutputInvalidException e) {
                    // 校验失败不重试：步骤降级，流水线终止为 PARTIAL_SUCCESS（手册 §3.6）
                    logStep(t.getId(), spec, seq, "DEGRADED", 0, null, e.getMessage(), null);
                    registry.counter("sv.agent.step", "agent", spec.agentCode(), "status", "DEGRADED")
                            .increment();
                    degraded = true;
                    bus.publish(t.getTaskNo(), "step_failed",
                            Map.of("stepSeq", seq, "agent", spec.agentCode(), "reason", e.getMessage()));
                    break;
                }
            }

            if (last != null) {
                persistMessage(t, steps.size() + 1, AgentCode.ORCHESTRATOR.name(), "USER",
                        AgentMessage.MsgType.FINAL, last);
            }
            finish(t, degraded ? "PARTIAL_SUCCESS" : "SUCCESS", null);
            bus.publish(t.getTaskNo(), "done", Map.of("status", t.getStatus(),
                    "payload", last == null ? mapper.nullNode() : last));
        } catch (Exception e) {
            log.error("task {} failed", t.getTaskNo(), e);
            finish(t, "FAILED", abbreviate(e.getMessage()));
            bus.publish(t.getTaskNo(), "error", Map.of("message", "任务执行失败"));
        }
    }

    private JsonNode executeStep(TaskInstanceEntity t, StepSpec spec, int seq,
                                 JsonNode input, JsonNode prev) {
        var agent = agents.require(spec.agentCode());
        AgentMessage request = AgentMessage.of(t.getId(), seq, AgentCode.ORCHESTRATOR.name(),
                spec.agentCode(), AgentMessage.MsgType.REQUEST,
                prev != null ? mergeInput(input, prev) : input);
        persistMessage(t, seq, AgentCode.ORCHESTRATOR.name(), spec.agentCode(),
                AgentMessage.MsgType.REQUEST, request.payload());
        bus.publish(t.getTaskNo(), "step_started", Map.of("stepSeq", seq, "agent", spec.agentCode()));

        AgentRuntime rt = new AgentRuntime(t.getId(), t.getUserId(), t.getTaskNo(), spec);
        long t0 = System.currentTimeMillis();
        RuntimeException lastErr = null;
        LlmUsageCollector.begin();   // O3：本步所有 LLM 应答用量归集后随步骤日志落库
        try {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    JsonNode out = agent.run(request, rt);
                    long cost = System.currentTimeMillis() - t0;
                    logStep(t.getId(), spec, seq, "SUCCESS", attempt, cost, null, LlmUsageCollector.drain());
                    registry.timer("sv.agent.step", "agent", spec.agentCode(), "status", "SUCCESS")
                            .record(java.time.Duration.ofMillis(cost));
                    persistMessage(t, seq, spec.agentCode(), nextConsumer(spec, seq),
                            AgentMessage.MsgType.MIDDLE_RESULT, out);
                    bus.publish(t.getTaskNo(), "middle_result",
                            Map.of("stepSeq", seq, "agent", spec.agentCode(), "payload", out));
                    return out;
                } catch (LlmUnavailableException e) {
                    lastErr = e;
                    logStep(t.getId(), spec, seq, "FAILED", attempt, null,
                            abbreviate(e.getMessage()), LlmUsageCollector.drain());
                    registry.counter("sv.agent.step", "agent", spec.agentCode(), "status", "FAILED")
                            .increment();
                    if (attempt < MAX_ATTEMPTS) {
                        sleep(BACKOFF_MS[attempt - 1]);
                        bus.publish(t.getTaskNo(), "step_retry",
                                Map.of("stepSeq", seq, "agent", spec.agentCode(), "attempt", attempt + 1));
                    }
                }
            }
            throw lastErr;
        } finally {
            LlmUsageCollector.clear();
        }
    }

    /** 步骤输出流向下一步骤；末步流向归档 Agent（M4 强制追加 RISK_ARCHIVE 步骤时在此扩展） */
    private String nextConsumer(StepSpec spec, int seq) { return "ORCHESTRATOR"; }

    private JsonNode mergeInput(JsonNode input, JsonNode prev) {
        var merged = input.deepCopy();
        if (merged.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) merged)
                .set("prev", prev);
        return merged;
    }

    private JsonNode promoteDiaryText(JsonNode base, JsonNode out) {
        if (out == null || !out.hasNonNull("diaryText") || base.hasNonNull("diaryText")) return base;
        base = base.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) base).set("diaryText", out.get("diaryText"));
        return base;
    }

    /** 某步已落库的 MIDDLE_RESULT（重跑续接用）；无则 null */
    private JsonNode middleResult(TaskInstanceEntity t, int seq) {
        return msgRepo.findByTaskIdOrderByStepSeqAscIdAsc(t.getId()).stream()
                .filter(m -> m.getStepSeq() == seq && "MIDDLE_RESULT".equals(m.getMsgType()))
                .findFirst()
                .map(m -> decrypt(t.getUserId(), m))
                .orElse(null);
    }

    private void persistMessage(TaskInstanceEntity t, int seq, String from, String to,
                                AgentMessage.MsgType type, JsonNode payload) {
        try {
            AgentMessageEntity m = new AgentMessageEntity();
            m.setMessageNo(Ulid.next());
            m.setTaskId(t.getId());
            m.setStepSeq(seq);
            m.setFromAgent(from);
            m.setToAgent(to);
            m.setMsgType(type.name());
            m.setPayloadEnc(crypto.encryptUserField(t.getUserId(), mapper.writeValueAsString(payload)));
            msgRepo.save(m);
        } catch (Exception e) {
            log.error("persist agent_message failed", e);
        }
    }

    private void logStep(long taskId, StepSpec spec, int seq, String status,
                         int attempt, Long costMs, String err, LlmUsageCollector.Accum usage) {
        TaskStepLogEntity l = new TaskStepLogEntity();
        l.setTaskId(taskId);
        l.setStepSeq(seq);
        l.setAgentCode(spec.agentCode());
        l.setStepCode(spec.stepCode());
        l.setStatus(status);
        l.setAttempt((short) attempt);
        l.setCostMs(costMs == null ? null : costMs.intValue());
        l.setErrorMsg(err);
        if (usage != null && usage.calls() > 0) {   // O3 成本回填：无 LLM 调用的步骤保持 null
            l.setLlmCalls((short) usage.calls());
            l.setTokensIn(usage.tokensIn());
            l.setTokensOut(usage.tokensOut());
            l.setModel(usage.model());
        }
        stepRepo.save(l);
    }

    public TaskInstanceEntity byNo(String taskNo) {
        return taskRepo.findByTaskNo(taskNo)
                .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
    }

    /** 结果查询：解密回传（仅资源属主可访问，属主断言在 Controller） */
    public JsonNode finalPayload(TaskInstanceEntity t) {
        return msgRepo.findByTaskIdOrderByStepSeqAscIdAsc(t.getId()).stream()
                .filter(m -> m.getMsgType().equals("FINAL"))
                .reduce((a, b) -> b)
                .map(m -> decrypt(t.getUserId(), m))
                .orElse(null);
    }

    private JsonNode decrypt(long userId, AgentMessageEntity m) {
        try {
            return mapper.readTree(crypto.decryptUserField(userId, m.getPayloadEnc()));
        } catch (Exception e) {
            return mapper.createObjectNode().put("error", "解密失败");
        }
    }

    public void cancel(TaskInstanceEntity t) {
        if (t.getStatus().equals("SUCCESS") || t.getStatus().equals("FAILED")) {
            throw new BizException(ErrorCode.BAD_PARAMS, "任务已结束，无法取消");
        }
        finish(t, "CANCELLED", null);
        bus.publish(t.getTaskNo(), "done", Map.of("status", "CANCELLED"));
    }

    private boolean isCancelled(long taskId) {
        return taskRepo.findById(taskId).map(x -> "CANCELLED".equals(x.getStatus())).orElse(true);
    }

    private void finish(TaskInstanceEntity t, String status, String err) {
        t.setStatus(status);
        t.setErrorMsg(err);
        t.setFinishedAt(Instant.now());
        taskRepo.save(t);
        if (t.getStartedAt() != null) {
            registry.timer("sv.task.duration", "pipeline", t.getPipelineCode(), "status", status)
                    .record(java.time.Duration.between(t.getStartedAt(), t.getFinishedAt()));
        }
        bus.publish(t.getTaskNo(), "task_status", Map.of("status", status));
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
    private static String abbreviate(String s) { return s == null ? null : s.substring(0, Math.min(500, s.length())); }
    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
