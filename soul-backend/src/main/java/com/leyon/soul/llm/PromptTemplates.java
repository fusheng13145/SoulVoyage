package com.leyon.soul.llm;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Prompt 模板中心：classpath prompts/*.txt，{{var}} 占位渲染，缓存加载 */
@Component
public class PromptTemplates {

    /** 全局边界策略：所有 Agent 系统提示词强制注入（手册 §1.3 三层硬约束之 Prompt 层） */
    public static final String DISCLAIMER_POLICY = """
            【强制策略】你是心理自助成长平台的辅助模块，不是医生或咨询师。
            1. 禁止给出任何精神/心理疾病诊断结论（如抑郁症、焦虑症、PTSD 等病名判断）。
            2. 禁止提供药物治疗建议或替代专业咨询的承诺。
            3. 语言保持中性、支持性、非评判；结论以"可能/倾向于"等假设语气表达。
            4. 仅输出 JSON，不要输出任何解释文字或 markdown 代码块标记之外的内容。
            """;

    /**
     * 模板 → 输出 Schema。真实模型（尤其本地小模型）仅凭规则描述无法稳定猜出字段层级，
     * 把 Schema 注入系统提示词，保证 Prompt 与 OutputValidator 的契约同源。
     */
    private static final Map<String, String> SCHEMA_FILES = Map.of(
            "emotion_v1", "emotion_result.json",
            "trace_v1", "trace_result.json",
            "support_v1", "support_plan.json",
            "simulate_review_v1", "simulate_review_result.json",
            "growth_letter_v1", "growth_letter.json",
            "companion_v1", "companion_reply.json",
            "npc_v1", "npc_reply.json");

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Map<String, String> schemaCache = new ConcurrentHashMap<>();

    public String system(String templateName) {
        String base = DISCLAIMER_POLICY + "\n" + load(templateName);
        String schemaFile = SCHEMA_FILES.get(templateName);
        if (schemaFile == null) return base;
        return base + "\n\n【输出 JSON Schema（必须严格符合；字段名与层级不得增删改）】\n"
                + loadSchema(schemaFile);
    }

    public String render(String raw, Map<String, String> vars) {
        String out = raw;
        for (var e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return out;
    }

    private String load(String name) {
        return cache.computeIfAbsent(name, k -> readClasspath("prompts/" + k + ".txt"));
    }

    private String loadSchema(String name) {
        return schemaCache.computeIfAbsent(name, k -> readClasspath("schemas/" + k));
    }

    private String readClasspath(String path) {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("classpath resource missing: " + path, e);
        }
    }
}
