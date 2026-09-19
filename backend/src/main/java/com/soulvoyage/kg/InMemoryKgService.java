package com.soulvoyage.kg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版知识图谱（Neo4j 就绪前的占位实现，词条与 deploy/neo4j/seed.cypher 同源）。
 * 召回打分：情绪精确命中 +2 / 每个事件标签命中压力源关联 +1，取 TopN。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InMemoryKgService implements KgSearchService {

    private final ObjectMapper mapper;
    private final List<DistortionEntry> entries = new ArrayList<>();
    private final Map<String, String> stressorMap = new HashMap<>();
    private final Map<String, DistortionEntry> byId = new HashMap<>();

    record DistortionEntry(String kgNodeId, String name, String definition, String typicalSignature,
                           String socraticTemplate, List<String> emotions) {}

    @PostConstruct
    void load() throws Exception {
        JsonNode root = mapper.readTree(
                new String(new ClassPathResource("kg/cognitive_distortions.json").getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8));
        root.path("stressorMap").fields().forEachRemaining(f -> stressorMap.put(f.getKey(), f.getValue().asText()));
        for (JsonNode d : root.path("distortions")) {
            DistortionEntry e = new DistortionEntry(
                    d.get("kgNodeId").asText(), d.get("name").asText(), d.get("definition").asText(),
                    d.get("typicalSignature").asText(), d.get("socraticTemplate").asText(),
                    mapper.convertValue(d.path("emotions"), List.class));
            entries.add(e);
            byId.put(e.kgNodeId(), e);
        }
        log.info("in-memory KG loaded: {} distortions, {} stressor mappings", entries.size(), stressorMap.size());
    }

    @Override
    public List<DistortionCard> distortionsFor(String primaryEmotion, List<String> eventTags, int maxCount) {
        List<String> stressors = stressorsFor(eventTags);
        return entries.stream()
                .map(e -> {
                    int score = 0;
                    if (e.emotions().contains(primaryEmotion)) score += 2;
                    for (String s : stressors) if (e.emotions().contains(s)) score += 1;
                    if (e.typicalSignature().contains(primaryEmotion)) score += 1;
                    return Map.entry(e, score);
                })
                .filter(en -> en.getValue() > 0)
                .sorted(Comparator.comparingInt((Map.Entry<DistortionEntry, Integer> en) -> -en.getValue())
                        .thenComparing(en -> en.getKey().kgNodeId()))
                .limit(maxCount)
                .map(en -> new DistortionCard(en.getKey().kgNodeId(), en.getKey().name(),
                        en.getKey().definition(), en.getKey().typicalSignature(),
                        en.getKey().socraticTemplate()))
                .toList();
    }

    @Override
    public List<String> stressorsFor(List<String> eventTags) {
        return eventTags.stream()
                .map(stressorMap::get)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    @Override
    public List<String> stressorOptions() {
        return stressorMap.values().stream().distinct().sorted().toList();
    }

    @Override
    public boolean distortionExists(String kgNodeId) {
        return byId.containsKey(kgNodeId);
    }
}
