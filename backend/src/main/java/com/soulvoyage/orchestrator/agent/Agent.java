package com.soulvoyage.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.soulvoyage.orchestrator.protocol.AgentMessage;

/** 业务推理单元统一抽象：按需唤起、无驻留会话、纯函数式 run(输入)→输出 */
public interface Agent {

    String code();

    /**
     * @param request type=REQUEST 的结构化消息
     * @return Agent 业务输出（尚未校验），调度中心负责 Schema 校验与落库
     * @throws LlmUnavailableException 可重试的模型故障
     * @throws OutputInvalidException  不可重试的输出校验失败
     */
    JsonNode run(AgentMessage request, AgentRuntime rt);
}
