package com.soulvoyage.agent.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.companion.CompanionTurnEntity;
import com.soulvoyage.domain.companion.CompanionTurnRepository;
import com.soulvoyage.llm.OutputValidator;
import com.soulvoyage.orchestrator.agent.Agent;
import com.soulvoyage.orchestrator.agent.AgentRuntime;
import com.soulvoyage.orchestrator.protocol.AgentMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 漫聊陪伴 Agent（手册 §4.6，第六个 Agent）在 COMPANION_PIPELINE 中的"摘要提取"步骤：
 * 确定性拼装被分析轮次（排除「这句别分析」），不调 LLM——对话原文不进任何日志明文，
 * 输出 diaryText 字段供下游 EMOTION/TRACE/RISK_ARCHIVE 按既有契约消费（复用大于新建）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanionAgent implements Agent {

    private static final int DIGEST_CHAR_CAP = 4000;

    private final CompanionTurnRepository turnRepo;
    private final CryptoService crypto;
    private final OutputValidator validator;
    private final ObjectMapper mapper;

    @Override
    public String code() { return "COMPANION"; }

    @Override
    public JsonNode run(AgentMessage request, AgentRuntime rt) {
        long sessionId = request.payload().path("sessionId").asLong(0);
        List<CompanionTurnEntity> analyzed =
                turnRepo.findBySessionIdAndNoAnalyzeOrderByTurnNoAsc(sessionId, (short) 0);
        long excluded = turnRepo.findBySessionIdOrderByTurnNoAsc(sessionId).size() - analyzed.size();

        StringBuilder sb = new StringBuilder();
        for (CompanionTurnEntity t : analyzed) {
            String user;
            try {
                user = crypto.decryptUserField(rt.userId(), t.getUserTextEnc());
            } catch (Exception e) {
                continue;   // 密钥销毁后的历史段：跳过不可解轮次，不炸管道
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(user);
            if (sb.length() >= DIGEST_CHAR_CAP) break;
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("diaryText", sb.length() > DIGEST_CHAR_CAP ? sb.substring(0, DIGEST_CHAR_CAP) : sb.toString());
        out.put("sessionId", sessionId);
        out.put("analyzedTurns", analyzed.size());
        out.put("excludedTurns", (int) excluded);
        return validator.validate(rt.spec().outputSchema(), out.toString());
    }
}
