package com.soulvoyage.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.common.util.Ulid;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.task.AgentMessageEntity;
import com.soulvoyage.domain.task.AgentMessageRepository;
import com.soulvoyage.domain.task.TaskInstanceEntity;
import com.soulvoyage.domain.task.TaskStepLogEntity;
import com.soulvoyage.domain.task.TaskStepLogRepository;
import com.soulvoyage.llm.LlmUsageCollector;
import com.soulvoyage.orchestrator.agent.AgentRuntime;
import com.soulvoyage.orchestrator.agent.LlmUnavailableException;
import com.soulvoyage.orchestrator.pipeline.StepSpec;
import com.soulvoyage.orchestrator.protocol.AgentMessage;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 单步执行器（B-03）：REQUEST 落库、step_started、可重试异常退避重试、
 * 步骤日志/指标、MIDDLE_RESULT 落库与事件。事务边界与拆分前一致（不在事务内）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskStepRunner {

    private static final int MAX_ATTEMPTS = 3;          // 首次 + 2 次重试（仅可重试异常）
    private static final long[] BACKOFF_MS = {2000, 8000};
    private static final String ORCHESTRATOR = AgentCode.ORCHESTRATOR.name();

    private final AgentRegistry agents;
    private final TaskStepLogRepository stepRepo;
    private final AgentMessageRepository msgRepo;
    private final CryptoService crypto;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;
    private final TaskEventPublisher events;

    JsonNode execute(TaskInstanceEntity t, StepSpec spec, int seq, JsonNode input, JsonNode prev) {
        var agent = agents.require(spec.agentCode());
        AgentMessage request = AgentMessage.of(t.getId(), seq, ORCHESTRATOR,
                spec.agentCode().name(), AgentMessage.MsgType.REQUEST,
                prev != null ? mergeInput(input, prev) : input);
        persistMessage(t, seq, ORCHESTRATOR, spec.agentCode().name(),
                AgentMessage.MsgType.REQUEST, request.payload());
        events.stepStarted(t.getTaskNo(), seq, spec.agentCode());

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
                    registry.timer("sv.agent.step", "agent", spec.agentCode().name(), "status", "SUCCESS")
                            .record(Duration.ofMillis(cost));
                    persistMessage(t, seq, spec.agentCode().name(), ORCHESTRATOR,
                            AgentMessage.MsgType.MIDDLE_RESULT, out);
                    events.middleResult(t.getTaskNo(), seq, spec.agentCode(), out);
                    return out;
                } catch (LlmUnavailableException e) {
                    lastErr = e;
                    logStep(t.getId(), spec, seq, "FAILED", attempt, null,
                            abbreviate(e.getMessage()), LlmUsageCollector.drain());
                    registry.counter("sv.agent.step", "agent", spec.agentCode().name(), "status", "FAILED")
                            .increment();
                    if (attempt < MAX_ATTEMPTS) {
                        sleep(BACKOFF_MS[attempt - 1]);
                        events.stepRetry(t.getTaskNo(), seq, spec.agentCode(), attempt + 1);
                    }
                }
            }
            throw lastErr;
        } finally {
            LlmUsageCollector.clear();
        }
    }

    /** 输出校验失败：不重试，记 DEGRADED 日志与指标（事件由执行服务发布）。 */
    void degrade(TaskInstanceEntity t, StepSpec spec, int seq, String reason) {
        logStep(t.getId(), spec, seq, "DEGRADED", 0, null, reason, null);
        registry.counter("sv.agent.step", "agent", spec.agentCode().name(), "status", "DEGRADED")
                .increment();
    }

    /** 整条流水线收口：最后一步产出以 FINAL 落库（seq = 步骤数 + 1）。 */
    void persistFinal(TaskInstanceEntity t, int stepSeq, JsonNode payload) {
        persistMessage(t, stepSeq, ORCHESTRATOR, "USER", AgentMessage.MsgType.FINAL, payload);
    }

    private JsonNode mergeInput(JsonNode input, JsonNode prev) {
        var merged = input.deepCopy();
        if (merged.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) merged)
                .set("prev", prev);
        return merged;
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
        l.setAgentCode(spec.agentCode().name());
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

    private static String abbreviate(String s) {
        return s == null ? null : s.substring(0, Math.min(500, s.length()));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
