package com.soulvoyage.orchestrator;

import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.orchestrator.agent.Agent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AgentRegistry {

    private final Map<String, Agent> byCode;

    public AgentRegistry(List<Agent> agents) {
        this.byCode = agents.stream().collect(Collectors.toMap(Agent::code, Function.identity()));
    }

    public Agent require(String code) {
        Agent a = byCode.get(code);
        if (a == null) throw new BizException(ErrorCode.PIPELINE_NOT_ALLOWED, "未注册的 Agent: " + code);
        return a;
    }
}
