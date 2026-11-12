package com.leyon.soul.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 与输出 Schema 的契约：真实模型必须从系统提示词看到完整字段结构，
 * 否则只靠 mock 通过、真实模型必然在 OutputValidator 处降级。
 */
class PromptTemplatesTest {

    private final PromptTemplates templates = new PromptTemplates();

    @Test
    @DisplayName("所有带输出契约的模板都注入对应 JSON Schema")
    void systemInjectsOutputSchema() {
        assertTrue(templates.system("trace_v1").contains("\"stressors\""));
        assertTrue(templates.system("emotion_v1").contains("\"primaryEmotion\""));
        assertTrue(templates.system("support_v1").contains("\"matchedExercises\""));
        assertTrue(templates.system("simulate_review_v1").contains("\"turnScores\""));
        assertTrue(templates.system("growth_letter_v1").contains("\"letter\""));
        assertTrue(templates.system("companion_v1").contains("\"reply\""));
        assertTrue(templates.system("npc_v1").contains("\"reply\""));
        assertTrue(templates.system("trace_v1").contains("【输出 JSON Schema"));
    }

    @Test
    @DisplayName("未知模板直接失败，避免静默输出无契约提示词")
    void unknownTemplateFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> templates.system("no_such_template"));
    }
}
