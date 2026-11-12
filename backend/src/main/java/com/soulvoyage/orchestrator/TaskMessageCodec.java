package com.soulvoyage.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.task.AgentMessageEntity;
import com.soulvoyage.domain.task.AgentMessageRepository;
import com.soulvoyage.domain.task.TaskInstanceEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 消息读取/解密（B-03）：执行续接与结果查询共用，避免多处重复解密逻辑。 */
@Component
@RequiredArgsConstructor
public class TaskMessageCodec {

    private final AgentMessageRepository msgRepo;
    private final CryptoService crypto;
    private final ObjectMapper mapper;

    public JsonNode decrypt(long userId, AgentMessageEntity m) {
        try {
            return mapper.readTree(crypto.decryptUserField(userId, m.getPayloadEnc()));
        } catch (Exception e) {
            return mapper.createObjectNode().put("error", "解密失败");
        }
    }

    /** 某步已落库的 MIDDLE_RESULT（重跑续接用）；无则 null */
    public JsonNode middleResult(TaskInstanceEntity t, int seq) {
        return msgRepo.findByTaskIdOrderByStepSeqAscIdAsc(t.getId()).stream()
                .filter(m -> m.getStepSeq() == seq && "MIDDLE_RESULT".equals(m.getMsgType()))
                .findFirst()
                .map(m -> decrypt(t.getUserId(), m))
                .orElse(null);
    }

    /** 原始输入 = 首步 REQUEST 消息（executeStep 在无上游产出时即按原输入落库） */
    public JsonNode firstRequest(TaskInstanceEntity t) {
        return msgRepo.findByTaskIdOrderByStepSeqAscIdAsc(t.getId()).stream()
                .filter(m -> m.getStepSeq() == 1 && "REQUEST".equals(m.getMsgType()))
                .findFirst()
                .map(m -> decrypt(t.getUserId(), m))
                .orElse(null);
    }

    /** FINAL 取最后一条（append-only 可能有多条重跑结果） */
    public JsonNode finalPayload(TaskInstanceEntity t) {
        return msgRepo.findByTaskIdOrderByStepSeqAscIdAsc(t.getId()).stream()
                .filter(m -> m.getMsgType().equals("FINAL"))
                .reduce((a, b) -> b)
                .map(m -> decrypt(t.getUserId(), m))
                .orElse(null);
    }
}
