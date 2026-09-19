package com.soulvoyage.llm;

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

    @Override
    public LlmResponse chat(LlmRequest req) {
        long t0 = System.currentTimeMillis();
        sleep(300);   // 模拟网络延迟，便于观察 SSE 进度体验
        String content = switch (req.template()) {
            case "emotion_v1" -> "```json\n" + emotionMock(req.user()) + "\n```";
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

    private boolean contains(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    private int estTokens(String s) { return Math.max(1, s.length() / 2); }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
