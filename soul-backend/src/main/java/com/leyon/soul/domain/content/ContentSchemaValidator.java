package com.leyon.soul.domain.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * H-04 发布前内容 schema 校验：类型化字段、长度、闭集与交叉引用。
 * 只读纯函数（除压力源闭集来自 ContentStore），不修改 live 数据；errors 阻断发布，warnings 仅提示。
 * 注意：C-04 的 schemas/ 目录是模型输出闭集，本类不读写它。
 */
@Component
@RequiredArgsConstructor
public class ContentSchemaValidator {

    public record Result(boolean valid, List<String> errors, List<String> warnings, JsonNode normalized) {}

    private static final Set<String> KG_TYPES =
            Set.of("DISTORTION", "PSY_TOPIC", "COMM_CASE", "STRENGTH_TECH");
    private static final Set<String> DIFFICULTIES = Set.of("MILD", "NORMAL", "HARD");
    private static final Set<String> GOAL_DIMENSIONS =
            Set.of("LISTEN", "BOUNDARY", "EMPATHY", "CONCESSION");
    private static final Set<String> EXERCISE_EMOTIONS = Set.of(
            "ANXIETY", "FEAR", "ACUTE_STRESS", "ANGER", "HIGH_PRESSURE", "INSOMNIA",
            "SADNESS", "LOW_MOOD", "SHAME", "GUILT", "LONELINESS", "NUMBNESS", "CONFUSED");

    private final ContentStore store;
    private final ObjectMapper mapper;

    public Result validate(ContentType type, String code, JsonNode input) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (input == null || !input.isObject()) {
            errors.add("内容必须是 JSON 对象");
            return new Result(false, List.copyOf(errors), List.copyOf(warnings), input);
        }
        ObjectNode obj = input.deepCopy();
        validateCode(type, code, errors);
        switch (type) {
            case KG -> validateKg(obj, code, errors, warnings);
            case SCENE -> validateScene(obj, errors, warnings);
            case EXERCISE -> validateExercise(obj, errors, warnings);
        }
        normalizeStatus(obj, errors);
        return new Result(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings), obj);
    }

    // ---------------- KG ----------------

    /** 草稿保存只要求 code 合法；payload 允许暂时不完整，发布前再完整校验。 */
    public List<String> codeErrors(ContentType type, String code) {
        List<String> errors = new ArrayList<>();
        validateCode(type, code, errors);
        return List.copyOf(errors);
    }

    private void validateKg(ObjectNode obj, String code, List<String> errors, List<String> warnings) {
        String nodeType = text(obj, "type", 16, true, errors);
        if (!nodeType.isBlank() && !KG_TYPES.contains(nodeType)) {
            errors.add("type 必须是 " + String.join("/", KG_TYPES));
            return;
        }
        String prefix = switch (nodeType) {
            case "DISTORTION" -> "cd_";
            case "PSY_TOPIC" -> "psy_";
            case "COMM_CASE" -> "cc_";
            case "STRENGTH_TECH" -> "st_";
            default -> "";
        };
        if (!prefix.isBlank() && code != null && !code.startsWith(prefix)) {
            errors.add("code 前缀应为 " + prefix + "（type=" + nodeType + "）");
        }
        text(obj, "name", 64, true, errors);

        JsonNode payload = obj.get("payload");
        if (payload == null || !payload.isObject()) {
            errors.add("payload 必须是 JSON 对象");
            return;
        }
        if (jsonLength(payload) > 4000) errors.add("payload 超过 4000 字符上限");
        switch (nodeType) {
            case "DISTORTION" -> {
                text(payload, "definition", 500, true, errors);
                stringArray(payload, "socraticTemplates", true, errors);
                if (!payload.hasNonNull("typicalSignature")) warnings.add("建议补充 typicalSignature（典型句式）");
                if (!payload.hasNonNull("emotions")) warnings.add("建议补充 emotions（适用情绪）");
            }
            case "PSY_TOPIC" -> {
                text(payload, "summary", 2000, true, errors);
                text(payload, "microAction", 300, true, errors);
                stringArray(payload, "aboutTags", true, errors);
                if (!payload.hasNonNull("readingSec")) {
                    warnings.add("未填写 readingSec，默认按 40 秒展示");
                } else if (payload.path("readingSec").asInt(0) < 10 || payload.path("readingSec").asInt(0) > 300) {
                    errors.add("readingSec 需在 10-300 秒之间");
                }
            }
            case "COMM_CASE" -> {
                text(payload, "situation", 1000, true, errors);
                text(payload, "unhelpful", 1000, true, errors);
                text(payload, "helpful", 1000, true, errors);
                stringArray(payload, "distortionRefs", true, errors);
                stringArray(payload, "techniqueRefs", false, errors);
                stringArray(payload, "aboutTags", true, errors);
            }
            case "STRENGTH_TECH" -> {
                text(payload, "description", 1000, true, errors);
                text(payload, "whenToUse", 300, true, errors);
                stringArray(payload, "steps", true, errors);
                if (!payload.hasNonNull("emotions")) warnings.add("建议补充 emotions（适用情绪）");
            }
            default -> {
                // type 已在上面拦截
            }
        }
    }

    // ---------------- 场景卡 ----------------

    private void validateScene(ObjectNode obj, List<String> errors, List<String> warnings) {
        text(obj, "title", 64, true, errors);
        text(obj, "description", 512, true, errors);

        JsonNode difficulties = stringArray(obj, "difficulties", true, errors);
        if (difficulties != null) {
            for (JsonNode d : difficulties) {
                if (!DIFFICULTIES.contains(d.asText())) {
                    errors.add("difficulties 只能包含 MILD/NORMAL/HARD");
                    break;
                }
            }
        }

        JsonNode persona = obj.get("persona");
        if (persona == null || !persona.isObject()) {
            errors.add("persona 必须是 JSON 对象");
        }
        text(obj, "npcName", 32, true, errors);
        text(obj, "relation", 32, true, errors);
        if (persona != null && persona.isObject()) {
            text(persona, "motivation", 300, true, errors);
            text(persona, "bottomLine", 300, true, errors);
            text(persona, "triggers", 300, true, errors);
            text(persona, "style", 300, true, errors);
        }

        JsonNode opening = obj.get("openingLines");
        if (opening == null || !opening.isObject()) {
            errors.add("openingLines 必须是 JSON 对象");
        } else if (difficulties != null) {
            for (JsonNode d : difficulties) {
                String key = d.asText();
                if (!opening.hasNonNull(key) || opening.path(key).asText("").isBlank()) {
                    errors.add("缺少 " + key + " 难度的开场白");
                }
            }
        }

        JsonNode goals = stringArray(obj, "goalDimensions", true, errors);
        if (goals != null) {
            for (JsonNode g : goals) {
                if (!GOAL_DIMENSIONS.contains(g.asText())) {
                    errors.add("goalDimensions 只能包含 LISTEN/BOUNDARY/EMPATHY/CONCESSION");
                    break;
                }
            }
        }

        int maxTurns = obj.path("maxTurns").asInt(20);
        if (maxTurns < 1 || maxTurns > 50) errors.add("maxTurns 需在 1-50 之间");
        obj.put("maxTurns", maxTurns);

        JsonNode tags = stringArray(obj, "tags", false, errors);
        if (tags != null && joinedLength(tags) > 128) errors.add("tags 合并后超过 128 字符");
        JsonNode recommended = stringArray(obj, "recommendedFor", false, errors);
        if (recommended != null) {
            if (joinedLength(recommended) > 128) errors.add("recommendedFor 合并后超过 128 字符");
            Set<String> closed = store.stressorDefinitions().keySet();
            if (!closed.isEmpty()) {
                for (JsonNode r : recommended) {
                    if (!closed.contains(r.asText())) {
                        errors.add("recommendedFor 含未知压力源: " + r.asText());
                    }
                }
            } else {
                warnings.add("压力源字典暂不可用，recommendedFor 未做闭集校验");
            }
        }

        if (jsonLength(obj) > 4000) errors.add("场景卡快照超过 4000 字符上限");
        if (obj.path("goalDimensions").toString().length() > 128) errors.add("goalDimensions 超过 128 字符");
    }

    // ---------------- 练习 ----------------

    private void validateExercise(ObjectNode obj, List<String> errors, List<String> warnings) {
        text(obj, "name", 64, true, errors);
        JsonNode emotions = stringArray(obj, "applyEmotions", true, errors);
        if (emotions != null) {
            if (jsonLength(emotions) > 256) errors.add("applyEmotions 超过 256 字符");
            for (JsonNode e : emotions) {
                if (!EXERCISE_EMOTIONS.contains(e.asText())) {
                    warnings.add("applyEmotions 含未登记标签: " + e.asText());
                }
            }
        }
        JsonNode steps = obj.get("steps");
        if (steps == null || !steps.isArray() || steps.isEmpty()) {
            errors.add("steps 至少需要 1 步");
        } else {
            int i = 0;
            for (JsonNode s : steps) {
                i++;
                if (!s.isObject()) {
                    errors.add("steps[" + i + "] 必须是对象");
                    continue;
                }
                if (s.path("step").asText("").isBlank()) errors.add("steps[" + i + "].step 不能为空");
                if (s.path("desc").asText("").isBlank()) errors.add("steps[" + i + "].desc 不能为空");
            }
            if (jsonLength(steps) > 4000) errors.add("steps 超过 4000 字符");
        }
        int duration = obj.path("durationMin").asInt(5);
        if (duration < 1 || duration > 60) errors.add("durationMin 需在 1-60 分钟之间");
        obj.put("durationMin", duration);
    }

    // ---------------- 通用 ----------------

    private void validateCode(ContentType type, String code, List<String> errors) {
        if (code == null || code.isBlank()) {
            errors.add("code 不能为空");
            return;
        }
        if (code.length() > 48) {
            errors.add("code 超过 48 字符");
            return;
        }
        switch (type) {
            case KG -> {
                if (!code.matches("^(cd|psy|cc|st)_[a-z0-9_]{1,45}$")) {
                    errors.add("KG code 需形如 cd_/psy_/cc_/st_ 前缀的小写编码");
                }
            }
            case SCENE -> {
                if (!code.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
                    errors.add("场景 code 需为大写字母/数字/下划线，长度 2-32");
                }
            }
            case EXERCISE -> {
                if (!code.matches("^ex_[a-z0-9_]{1,29}$")) {
                    errors.add("练习 code 需为 ex_ 前缀的小写编码");
                }
            }
        }
    }

    private void normalizeStatus(ObjectNode obj, List<String> errors) {
        JsonNode node = obj.get("status");
        int v = 1;
        if (node != null && !node.isNull()) {
            if (!node.isNumber()) {
                errors.add("status 只能是 1(上架) 或 2(下架)");
                return;
            }
            v = node.asInt();
        }
        if (v == 0 || v == 2) v = 2;
        if (v != 1 && v != 2) {
            errors.add("status 只能是 1(上架) 或 2(下架)");
            return;
        }
        obj.put("status", v);
    }

    private String text(JsonNode obj, String field, int max, boolean required, List<String> errors) {
        JsonNode n = obj.get(field);
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            if (required) errors.add(field + " 不能为空");
            return "";
        }
        String s = n.asText().trim();
        if (s.length() > max) errors.add(field + " 超过 " + max + " 字符");
        return s;
    }

    private JsonNode stringArray(JsonNode obj, String field, boolean required, List<String> errors) {
        JsonNode n = obj.get(field);
        if (n == null || !n.isArray()) {
            if (required) errors.add(field + " 必须是数组");
            return null;
        }
        if (required && n.isEmpty()) errors.add(field + " 至少需要 1 项");
        for (int i = 0; i < n.size(); i++) {
            if (!n.get(i).isTextual() || n.get(i).asText().isBlank()) {
                errors.add(field + "[" + (i + 1) + "] 必须是非空字符串");
            }
        }
        return n;
    }

    private int joinedLength(JsonNode arr) {
        int n = 0;
        for (JsonNode x : arr) n += x.asText().length() + 1;
        return Math.max(0, n - 1);
    }

    private int jsonLength(JsonNode node) {
        try {
            return mapper.writeValueAsString(node).length();
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }
}
