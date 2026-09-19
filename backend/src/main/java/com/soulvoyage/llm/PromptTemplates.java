package com.soulvoyage.llm;

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

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String system(String templateName) {
        return DISCLAIMER_POLICY + "\n" + load(templateName);
    }

    public String render(String raw, Map<String, String> vars) {
        String out = raw;
        for (var e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return out;
    }

    private String load(String name) {
        return cache.computeIfAbsent(name, k -> {
            try {
                return StreamUtils.copyToString(
                        new ClassPathResource("prompts/" + k + ".txt").getInputStream(),
                        StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("prompt template missing: " + k, e);
            }
        });
    }
}
