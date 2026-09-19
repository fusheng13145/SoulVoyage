package com.soulvoyage.agent.letter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.letter.GrowthLetterEntity;
import com.soulvoyage.domain.letter.GrowthLetterRepository;
import com.soulvoyage.domain.notify.NotificationService;
import com.soulvoyage.llm.LlmClient;
import com.soulvoyage.llm.OutputValidator;
import com.soulvoyage.llm.PromptTemplates;
import com.soulvoyage.orchestrator.agent.Agent;
import com.soulvoyage.orchestrator.agent.AgentRuntime;
import com.soulvoyage.orchestrator.agent.OutputInvalidException;
import com.soulvoyage.orchestrator.protocol.AgentMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 成长来信 Agent（下篇·G6）：周素材（服务层确定性组装）→ 一次 LLM 调用 → 第二人称信。
 * 幻觉约束沿用"闭集注入 + 反向校验"：信中「」引文必须逐字来自素材里的用户原句。
 */
@Component
@RequiredArgsConstructor
public class LetterAgent implements Agent {

    private final LlmClient llm;
    private final PromptTemplates templates;
    private final OutputValidator validator;
    private final GrowthLetterRepository letterRepo;
    private final CryptoService crypto;
    private final NotificationService notify;
    private final ObjectMapper mapper;

    @Override
    public String code() { return "LETTER"; }

    @Override
    public JsonNode run(AgentMessage request, AgentRuntime rt) {
        JsonNode input = request.payload();
        String statWeek = input.path("statWeek").asText("");
        JsonNode material = input.path("material");
        if (statWeek.isBlank() || material.isMissingNode()) {
            throw new OutputInvalidException("成长来信缺少周素材，放弃本轮");
        }
        // 幂等：同周已有信（定时重跑/重放）→ 直接回旧信，不再花一次 LLM
        var exist = letterRepo.findByUserIdAndStatWeek(rt.userId(), statWeek);
        if (exist.isPresent()) {
            ObjectNode out = mapper.createObjectNode();
            out.put("letterId", "gl_" + exist.get().getId());
            out.put("statWeek", statWeek);
            out.put("skipped", true);
            return out;
        }

        List<String> quotes = quoteList(material);
        String system = templates.render(templates.system("growth_letter_v1"),
                Map.of("material", materialBlock(material, quotes)));
        String user = material.toString();

        var resp = llm.chat(new LlmClient.LlmRequest("growth_letter_v1", system, user, 1500));
        JsonNode result = validator.validate(rt.spec().outputSchema(), resp.content());

        assertClosedSetQuotes(result.path("letter").asText(""), quotes);

        GrowthLetterEntity letter = new GrowthLetterEntity();
        letter.setUserId(rt.userId());
        letter.setStatWeek(statWeek);
        letter.setContentEnc(crypto.encryptUserField(rt.userId(), result.toString()));
        letter = letterRepo.save(letter);

        notify.push(rt.userId(), "LETTER", "letter:" + statWeek, "letter_ready",
                Map.of(), "/letters");

        ObjectNode out = result.deepCopy();
        out.put("letterId", "gl_" + letter.getId());
        out.put("statWeek", statWeek);
        return out;
    }

    private List<String> quoteList(JsonNode material) {
        List<String> out = new ArrayList<>();
        for (JsonNode q : material.path("quotes")) {
            String s = q.asText("");
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    private String materialBlock(JsonNode m, List<String> quotes) {
        if (m.path("insufficient").asBoolean(false) || quotes.isEmpty()) {
            var sb = new StringBuilder("（上周素材有限，只可回看统计值本身）\n");
            sb.append("记录数据点：").append(m.path("dataPoints").asInt(0)).append(" 个\n");
            appendStat(sb, m);
            return sb.append("（标注：素材有限）").toString();
        }
        var sb = new StringBuilder();
        sb.append("记录数据点：").append(m.path("dataPoints").asInt(0)).append(" 个，坚持记录 ")
                .append(m.path("streakDays").asInt(0)).append(" 天，完成练习 ")
                .append(m.path("exercisesDone").asInt(0)).append(" 次\n");
        appendStat(sb, m);
        sb.append("ta 上周写过的原句片段（逐字引用才可标注为 ta 说的）：\n");
        for (String q : quotes) sb.append("- 「").append(q).append("」\n");
        return sb.toString();
    }

    private void appendStat(StringBuilder sb, JsonNode m) {
        if (m.hasNonNull("avgValence")) {
            sb.append("本周心情均值 ").append(m.path("avgValence").asDouble())
                    .append("，上周 ").append(m.path("prevAvgValence").asDouble(0)).append('\n');
        }
        if (m.hasNonNull("emotionTop")) sb.append("出现最多的情绪：").append(m.path("emotionTop").asText()).append('\n');
        if (m.path("stressorTop").size() > 0) sb.append("主要压力源：").append(m.path("stressorTop").toString()).append('\n');
    }

    /** 反向闭集校验：信里以「」引用的一切，必须逐字命中素材原句（手册 G6"引用只取自用户自己的记录片段"） */
    public static void assertClosedSetQuotes(String letter, List<String> quotes) {
        int i = 0;
        while ((i = letter.indexOf('「', i)) >= 0) {
            int j = letter.indexOf('」', i + 1);
            if (j < 0) throw new OutputInvalidException("来信引文未闭合");
            String q = letter.substring(i + 1, j);
            final String qq = q;
            if (quotes.stream().noneMatch(src -> src.contains(qq))) {
                throw new OutputInvalidException("来信引用了素材之外的\"原句\": " + q);
            }
            i = j;
        }
    }
}
