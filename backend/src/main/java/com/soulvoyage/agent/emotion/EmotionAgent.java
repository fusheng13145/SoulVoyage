package com.soulvoyage.agent.emotion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.llm.LlmClient;
import com.soulvoyage.llm.OutputValidator;
import com.soulvoyage.llm.PromptTemplates;
import com.soulvoyage.orchestrator.agent.Agent;
import com.soulvoyage.orchestrator.agent.AgentRuntime;
import com.soulvoyage.orchestrator.protocol.AgentMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 情绪感知 Agent（手册 §4.1）：文本 → 结构化情绪 → 落情绪时序 → EmotionResult */
@Component
@RequiredArgsConstructor
public class EmotionAgent implements Agent {

    private final LlmClient llm;
    private final PromptTemplates templates;
    private final OutputValidator validator;
    private final EmotionTrajectoryRepository trajectoryRepo;
    private final ObjectMapper mapper;

    @Override
    public String code() { return "EMOTION"; }

    @Override
    public JsonNode run(AgentMessage request, AgentRuntime rt) {
        JsonNode input = request.payload();
        String text = input.path("diaryText").asText("");
        if (text.isBlank()) {
            // 兜底：空文本不猜测（手册 §4.1）
            return fallback();
        }
        var resp = llm.chat(new LlmClient.LlmRequest("emotion_v1",
                templates.system("emotion_v1"), text, 1200));
        JsonNode result = validator.validate(rt.spec().outputSchema(), resp.content());

        LocalDate date = input.hasNonNull("recordDate")
                ? LocalDate.parse(input.get("recordDate").asText()) : LocalDate.now();
        EmotionTrajectoryEntity t = new EmotionTrajectoryEntity();
        t.setUserId(rt.userId());
        t.setRecordDate(date);
        t.setSourceType(input.path("sourceType").asText("DIARY"));
        t.setSourceId(rt.taskId());
        t.setPrimaryEmotion(result.get("primaryEmotion").asText());
        t.setValence(BigDecimal.valueOf(result.get("valence").asDouble()));
        t.setIntensity(BigDecimal.valueOf(result.get("intensity").asDouble()));
        t.setEventTags(result.path("eventTags").toString());
        t = trajectoryRepo.save(t);

        var out = mapper.createObjectNode();
        out.put("primaryEmotion", result.get("primaryEmotion").asText());
        out.put("intensity", result.get("intensity").asDouble());
        out.put("valence", result.get("valence").asDouble());
        out.set("secondaryEmotions", result.path("secondaryEmotions").deepCopy());
        out.set("eventTags", result.path("eventTags").deepCopy());
        out.put("trajectoryPointId", "et_" + t.getId());
        return out;
    }

    private JsonNode fallback() {
        var node = mapper.createObjectNode();
        node.put("primaryEmotion", "平静");
        node.put("intensity", 0.1);
        node.put("valence", 0.0);
        node.putArray("secondaryEmotions");
        node.putArray("eventTags").addObject()
                .put("tag", "日常").put("evidence", "文本为空，采用保守兜底");
        return node;
    }
}
