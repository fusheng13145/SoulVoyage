package com.leyon.soul.orchestrator;

import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.orchestrator.agent.Agent;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentRegistry {

    private final Map<AgentCode, Agent> byCode;

    public AgentRegistry(List<Agent> agents) {
        Map<AgentCode, Agent> map = new EnumMap<>(AgentCode.class);
        for (Agent agent : agents) {
            Agent duplicate = map.put(agent.code(), agent);
            if (duplicate != null) {
                throw new IllegalStateException("Agent 编码重复注册: " + agent.code()
                        + " (" + duplicate.getClass().getSimpleName() + " / " + agent.getClass().getSimpleName() + ")");
            }
        }
        this.byCode = Map.copyOf(map);
    }

    public Agent require(AgentCode code) {
        Agent a = byCode.get(code);
        if (a == null) throw new BizException(ErrorCode.PIPELINE_NOT_ALLOWED, "未注册的 Agent: " + code);
        return a;
    }

    public boolean contains(AgentCode code) {
        return byCode.containsKey(code);
    }
}
