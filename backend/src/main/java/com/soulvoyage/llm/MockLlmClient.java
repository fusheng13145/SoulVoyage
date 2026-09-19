package com.soulvoyage.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.orchestrator.agent.LlmUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发期 Mock：按 prompt 模板路由到确定性样例输出，让全链路可跑、契约测试可回放。
 * 输出刻意贴近真实模型格式（含 markdown 包裹噪声），以验证解析器的健壮性。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "soulvoyage.llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public LlmResponse chat(LlmRequest req) {
        long t0 = System.currentTimeMillis();
        sleep(300);   // 模拟网络延迟，便于观察 SSE 进度体验
        String content = switch (req.template()) {
            case "emotion_v1" -> "```json\n" + emotionMock(req.user()) + "\n```";
            case "trace_v1" -> "```json\n" + traceMock(req.user()) + "\n```";
            default -> throw new LlmUnavailableException("Mock 未覆盖模板: " + req.template());
        };
        return new LlmResponse(content, "mock-llm-v1",
                estTokens(req.system() + req.user()), estTokens(content), System.currentTimeMillis() - t0);
    }

    private String emotionMock(String text) {
        String primary = "平静";
        double valence = 0.2, intensity = 0.3;
        if (contains(text, "烦", "气", "冲突", "吵", "骂")) { primary = "愤怒"; valence = -0.6; intensity = 0.75; }
        else if (contains(text, "压", "考试", "ddl", "答辩", "赶")) { primary = "焦虑"; valence = -0.5; intensity = 0.7; }
        else if (contains(text, "难过", "伤心", "哭", "失恋", "想家")) { primary = "悲伤"; valence = -0.7; intensity = 0.65; }
        else if (contains(text, "开心", "高兴", "顺利", "好消息", "感谢")) { primary = "喜悦"; valence = 0.8; intensity = 0.6; }
        else if (contains(text, "累", "麻木", "没感觉", "提不起")) { primary = "麻木"; valence = -0.2; intensity = 0.4; }
        String tag = switch (primary) {
            case "愤怒" -> "{\"tag\":\"人际冲突\",\"scene\":\"宿舍\",\"evidence\":\"（mock）检测到冲突类表述\"}";
            case "焦虑" -> "{\"tag\":\"学业压力\",\"scene\":\"学习\",\"evidence\":\"（mock）检测到压力类表述\"}";
            case "悲伤" -> "{\"tag\":\"关系失落\",\"scene\":\"亲密/家庭\",\"evidence\":\"（mock）检测到低落类表述\"}";
            case "喜悦" -> "{\"tag\":\"成就事件\",\"scene\":\"学习/生活\",\"evidence\":\"（mock）检测到积极表述\"}";
            default -> "{\"tag\":\"日常\",\"scene\":\"生活\",\"evidence\":\"（mock）无明显事件词\"}";
        };
        return """
        {"primaryEmotion":"%s","secondaryEmotions":[],"intensity":%.2f,"valence":%.2f,
         "eventTags":[%s],"profileDelta":{"stressorFreqUpdate":{}}}
        """.formatted(primary, intensity, valence, tag);
    }

    /** trace_v1 的 user 是 TraceAgent 组装的结构化 JSON：从注入的候选卡确定性拼装，保证契约测试可回放 */
    private String traceMock(String userJson) {
        JsonNode req;
        try {
            req = mapper.readTree(userJson);
        } catch (Exception e) {
            req = mapper.createObjectNode();
        }
        String emotion = req.path("emotionResult").path("primaryEmotion").asText("平静");
        String diary = req.path("diaryText").asText("");
        String excerpt = diary.length() > 40 ? diary.substring(0, 40) + "…" : diary;
        JsonNode candidates = req.path("kgCandidates");
        JsonNode stressorHint = req.path("stressorHint");

        var out = mapper.createObjectNode();
        ArrayNode stressors = out.putArray("stressors");
        if (stressorHint.isArray() && stressorHint.size() > 0) {
            String source = stressorHint.get(0).asText("其他");
            ObjectNode s = stressors.addObject();
            s.put("source", source);
            s.put("confidence", 0.62);
            s.putArray("evidence")
                    .add("（mock）事件标签指向「" + source + "」")
                    .add("（mock）日记节选：" + excerpt);
        }
        ArrayNode distortions = out.putArray("cognitiveDistortions");
        ArrayNode socratic = out.putArray("socraticQuestions");
        for (JsonNode c : candidates) {
            if (distortions.size() < 2) {
                ObjectNode d = distortions.addObject();
                d.put("name", c.path("name").asText());
                d.put("kgNodeId", c.path("kgNodeId").asText());
                d.put("trigger", "（mock）与典型句式「" + c.path("typicalSignature").asText() + "」相符");
                d.put("challengeQuestion", c.path("socraticTemplate").asText());
            }
            if (socratic.size() < 3) socratic.add(c.path("socraticTemplate").asText());
        }
        if (socratic.isEmpty()) socratic.add("如果一周后再看这件事，你会用哪个词形容当时的自己？");
        ObjectNode report = out.putObject("report");
        report.put("eventSummary", "（mock）日记摘录：" + excerpt);
        report.put("emotionSummary", "（mock）主导情绪为「" + emotion + "」，强度信号来自冲突/压力类表述。");
        report.put("thoughtSummary", candidates.isEmpty()
                ? "（mock）未匹配到候选认知误区卡。"
                : "（mock）想法中可能夹带「" + candidates.get(0).path("name").asText() + "」式的自动判断。");
        report.put("insight", "（mock）想法只是大脑的第一版草稿，不等于事实本身，可以拿出来检验。");
        report.put("suggestion", "（mock）今晚睡前写下三件顺利的小事，训练注意力的分配。");
        out.putArray("riskSignals");
        if (candidates.isEmpty() && stressorHint.size() == 0) out.put("insufficientEvidence", true);
        return out.toString();
    }

    private boolean contains(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    private int estTokens(String s) { return Math.max(1, s.length() / 2); }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
