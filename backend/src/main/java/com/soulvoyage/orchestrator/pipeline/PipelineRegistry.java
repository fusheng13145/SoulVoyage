package com.soulvoyage.orchestrator.pipeline;

import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PipelineRegistry {

    private final Map<String, TaskDefinition> byCode;

    public PipelineRegistry(List<TaskDefinition> definitions) {
        this.byCode = definitions.stream()
                .collect(Collectors.toMap(TaskDefinition::code, Function.identity()));
    }

    public TaskDefinition require(String code) {
        TaskDefinition d = byCode.get(code);
        if (d == null) throw new BizException(ErrorCode.PIPELINE_NOT_ALLOWED, "未注册的流水线: " + code);
        return d;
    }
}
