package com.leyon.soul.orchestrator.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.domain.crisis.CrisisState;
import com.leyon.soul.orchestrator.AgentRegistry;
import com.leyon.soul.orchestrator.PipelineCode;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 流水线注册表：强类型索引 + 启动时完整性校验（B-04）。 */
@Component
public class PipelineRegistry {

    private final Map<PipelineCode, TaskDefinition> byCode;
    private final AgentRegistry agents;
    private final ObjectMapper mapper;

    public PipelineRegistry(List<TaskDefinition> definitions, AgentRegistry agents, ObjectMapper mapper) {
        Map<PipelineCode, TaskDefinition> map = new EnumMap<>(PipelineCode.class);
        for (TaskDefinition d : definitions) {
            TaskDefinition duplicate = map.put(d.code(), d);
            if (duplicate != null) {
                throw new IllegalStateException("流水线编码重复注册: " + d.code()
                        + " (" + duplicate.getClass().getSimpleName() + " / " + d.getClass().getSimpleName() + ")");
            }
        }
        this.byCode = Map.copyOf(map);
        this.agents = agents;
        this.mapper = mapper;
    }

    /**
     * 启动即失败：枚举值必须全部有实现、每条流水线必须有步骤、每个步骤的 Agent 必须已注册。
     * 探测上下文取 NORMAL + 空对象，覆盖所有按危机态/入参分支的流水线。
     */
    @PostConstruct
    void validateWiring() {
        TaskContext probe = new TaskContext(0L, 0L, CrisisState.NORMAL, mapper.createObjectNode());
        for (PipelineCode code : PipelineCode.values()) {
            TaskDefinition d = byCode.get(code);
            if (d == null) throw new IllegalStateException("流水线未注册: " + code);
            List<StepSpec> steps = d.steps(probe);
            if (steps == null || steps.isEmpty()) throw new IllegalStateException("流水线没有步骤: " + code);
            for (StepSpec step : steps) {
                if (!agents.contains(step.agentCode())) {
                    throw new IllegalStateException("流水线 " + code + " 的步骤 " + step.stepCode()
                            + " 引用了未注册的 Agent: " + step.agentCode());
                }
            }
        }
    }

    public TaskDefinition require(PipelineCode code) {
        TaskDefinition d = byCode.get(code);
        if (d == null) throw new BizException(ErrorCode.PIPELINE_NOT_ALLOWED, "未注册的流水线: " + code);
        return d;
    }

    public TaskDefinition require(String code) {
        return require(PipelineCode.from(code));
    }
}
