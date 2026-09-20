package com.soulvoyage.agent.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.kg.KgSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** N2 后半场：SUPPORT 科普引用 kgSource 越界的温和纠正（不打断干预，就地换成候选首篇） */
class SupportKgSourceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<KgSearchService.PsyTopicCard> cards() {
        return List.of(
                new KgSearchService.PsyTopicCard("pt_beta", "B篇", "s", "m", List.of(), 40),
                new KgSearchService.PsyTopicCard("pt_alpha", "A篇", "s", "m", List.of(), 40));
    }

    private ObjectNode resultWithSource(String kgSource) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode edu = root.putObject("psyEducation");
        if (kgSource != null) edu.put("kgSource", kgSource);
        return root;
    }

    @Test
    void validSourcePassesThroughUnchanged() {
        ObjectNode r = resultWithSource("node:pt_alpha");
        SupportAgent.correctKgSource(r, cards());
        assertEquals("node:pt_alpha", r.path("psyEducation").path("kgSource").asText());
    }

    @Test
    void outOfSetSourceIsCorrectedToFirstCandidate() {
        ObjectNode r = resultWithSource("node:pt_invented");
        SupportAgent.correctKgSource(r, cards());
        assertEquals("node:pt_beta", r.path("psyEducation").path("kgSource").asText(),
                "越界引用应就地纠正为候选首篇");

        ObjectNode bare = resultWithSource("pt_beta");   // 无 node: 前缀也认
        SupportAgent.correctKgSource(bare, cards());
        assertEquals("pt_beta", bare.path("psyEducation").path("kgSource").asText());
    }

    @Test
    void missingSourceIsFilledAndNonObjectsTolerated() {
        ObjectNode r = resultWithSource(null);
        SupportAgent.correctKgSource(r, cards());
        assertEquals("node:pt_beta", r.path("psyEducation").path("kgSource").asText());

        // 结构不符（无 psyEducation 对象）时静默放过，不抛异常
        SupportAgent.correctKgSource(mapper.createObjectNode(), cards());
        SupportAgent.correctKgSource(mapper.nullNode(), cards());
    }
}
