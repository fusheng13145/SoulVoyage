package com.leyon.soul.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.soul.orchestrator.agent.OutputInvalidException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prompt 自由文本 → JSON Schema 之间的最后一道契约：围栏剥离、null 可选字段、诊断词拦截。 */
class OutputValidatorTest {

    private final OutputValidator validator = new OutputValidator(new ObjectMapper());

    @Test
    @DisplayName("可选字段为 null 时按缺失处理，不让真实模型的 null 误伤 Schema")
    void nullOptionalFieldIsPruned() {
        String raw = """
                {
                  "stressors": [
                    {"source": "学业压力", "confidence": 0.8, "evidence": ["ddl 堆在一起"], "timeRange": null}
                  ],
                  "cognitiveDistortions": [],
                  "socraticQuestions": ["这次评价里，哪一部分是事实？"],
                  "report": {
                    "eventSummary": "方案被当众修改",
                    "emotionSummary": "憋闷、睡不着",
                    "thoughtSummary": "觉得自己的努力被否定了",
                    "insight": "被修改不等于被否定",
                    "suggestion": "先睡一觉，明天再问一句具体建议"
                  },
                  "riskSignals": []
                }
                """;

        var out = validator.validate("trace_result.json", raw);

        assertEquals("学业压力", out.path("stressors").path(0).path("source").asText());
        assertEquals(false, out.path("stressors").path(0).has("timeRange"));
    }

    @Test
    @DisplayName("Schema 明确允许 null 时保留 null，不把风险回执的 riskEventId 清掉")
    void nullAllowedBySchemaIsPreserved() {
        String raw = """
                {
                  "riskLevel": "LOW",
                  "riskEventId": null,
                  "archived": {"emotionPoints": 1, "reports": 2, "messages": 3},
                  "encryption": {"algo": "AES-256-GCM", "keyVersion": "v1"},
                  "profileUpdated": true
                }
                """;

        var out = validator.validate("archive_receipt.json", raw);

        assertTrue(out.has("riskEventId"));
        assertTrue(out.get("riskEventId").isNull());
    }

    @Test
    @DisplayName("诊断词仍被拦截，不因 null 清理而放宽")
    void diagnosisWordsStillRejected() {
        String raw = """
                {
                  "stressors": [],
                  "cognitiveDistortions": [],
                  "socraticQuestions": ["发生了什么？"],
                  "report": {
                    "eventSummary": "你确诊了抑郁症",
                    "emotionSummary": "低落",
                    "thoughtSummary": "想法",
                    "insight": "洞察",
                    "suggestion": "建议"
                  },
                  "riskSignals": []
                }
                """;

        assertThrows(OutputInvalidException.class, () -> validator.validate("trace_result.json", raw));
    }
}
