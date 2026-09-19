package com.soulvoyage.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.soulvoyage.orchestrator.agent.OutputInvalidException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 输出校验层（手册 §1.3 第二层）：
 * 1) 剥离 markdown 代码块噪声 → JSON 解析
 * 2) JSON Schema 校验（闭集枚举/值域/必填）
 * 3) 诊断词表拦截——命中病名判断词直接判失败，防止模型越界下诊断结论
 */
@Component
public class OutputValidator {

    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```");
    private static final Set<String> DIAGNOSIS_WORDS = Set.of(
            "抑郁症", "焦虑症", "躁狂症", "精神分裂", "PTSD", "创伤后应激障碍",
            "双相情感障碍", "确诊", "患有", "患了");

    private final ObjectMapper mapper;
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final ConcurrentHashMap<String, JsonSchema> schemas = new ConcurrentHashMap<>();

    public OutputValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode validate(String schemaFile, String rawContent) {
        String json = stripFence(rawContent);
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception e) {
            throw new OutputInvalidException("输出不是合法 JSON", e);
        }
        JsonSchema schema = schemas.computeIfAbsent(schemaFile, this::loadSchema);
        Set<com.networknt.schema.ValidationMessage> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            throw new OutputInvalidException("Schema 校验失败: "
                    + errors.stream().findFirst().map(Object::toString).orElse(""));
        }
        String text = node.toString();
        for (String w : DIAGNOSIS_WORDS) {
            if (text.contains(w)) {
                throw new OutputInvalidException("输出含越界诊断表述「" + w + "」，已拦截");
            }
        }
        return node;
    }

    static String stripFence(String raw) {
        var m = CODE_FENCE.matcher(raw);
        if (m.find()) return m.group(1);
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return start >= 0 && end > start ? raw.substring(start, end + 1) : raw;
    }

    private JsonSchema loadSchema(String file) {
        try {
            return factory.getSchema(new ClassPathResource("schemas/" + file).getInputStream());
        } catch (Exception e) {
            throw new IllegalStateException("schema not found: " + file, e);
        }
    }
}
