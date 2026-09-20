package com.soulvoyage.domain.content;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.agent.simulate.SceneCard;
import com.soulvoyage.agent.support.Exercise;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 内容快照缓存（N4）：DB 三套内容表为运行时真源，content_meta['content:version'] 为失效信号。
 * 读路径按 5s TTL 比对版本号；管理端写口 bump() 同 JVM 即时生效，多实例由 30s 轮询兜底。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContentStore {

    public record DistortionEntry(String kgNodeId, String name, String definition, String typicalSignature,
                                  List<String> socraticTemplates, List<String> emotions) {}

    public record PsyEntry(String kgNodeId, String title, String summary, String microAction,
                           List<String> aboutTags, int readingSec) {}

    public record CaseEntry(String kgNodeId, String title, String scene, String situation,
                            String unhelpful, String helpful, List<String> distortionRefs,
                            List<String> techniqueRefs, List<String> aboutTags) {}

    public record TechEntry(String kgNodeId, String name, String description, String whenToUse,
                            List<String> emotions, List<String> steps) {}

    private record Snapshot(long version,
                            List<DistortionEntry> distortions,
                            Map<String, String> stressorMap,
                            List<PsyEntry> psyTopics,
                            List<CaseEntry> commCases,
                            List<TechEntry> techniques,
                            List<SceneCard> scenes,
                            List<Exercise> exercises) {
        static Snapshot empty() {
            return new Snapshot(-1, List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    private final KgNodeRepository kgRepo;
    private final ContentMetaRepository metaRepo;
    private final SceneCardRepository sceneRepo;
    private final ExerciseLibraryRepository exerciseRepo;
    private final ObjectMapper mapper;

    private volatile Snapshot snap = Snapshot.empty();
    private volatile long lastVersionCheckAt = 0;

    private static final long VERSION_TTL_MS = 5_000;

    // ---------------- public read api (each returns fresh-enough data) ----------------

    public List<DistortionEntry> distortions() { return current().distortions(); }

    public Map<String, String> stressorMap() { return current().stressorMap(); }

    public List<PsyEntry> psyTopics() { return current().psyTopics(); }

    public List<CaseEntry> commCases() { return current().commCases(); }

    public List<TechEntry> techniques() { return current().techniques(); }

    public List<SceneCard> scenes() { return current().scenes(); }

    public List<Exercise> exercises() { return current().exercises(); }

    /** 管理端写入口调用：抬版本 + 本 JVM 立即重载 */
    public synchronized long bump() {
        long next = versionOf(metaRepo.findByMetaKey("content:version")
                .map(ContentMetaEntity::getMetaValue).orElse("0")) + 1;
        ContentMetaEntity m = metaRepo.findByMetaKey("content:version")
                .orElseGet(() -> {
                    ContentMetaEntity n = new ContentMetaEntity();
                    n.setMetaKey("content:version");
                    return n;
                });
        m.setMetaValue(String.valueOf(next));
        metaRepo.save(m);
        snap = Snapshot.empty();          // 强制下次读重载
        lastVersionCheckAt = 0;
        return next;
    }

    /** 定时轮询兜底（多实例场景 30s 内收敛） */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void poll() {
        try {
            current();
        } catch (Exception e) {
            log.warn("content poll refresh failed", e);
        }
    }

    // ---------------- internals ----------------

    private Snapshot current() {
        long now = System.currentTimeMillis();
        if (snap.version() < 0 || now - lastVersionCheckAt > VERSION_TTL_MS) {
            synchronized (this) {
                now = System.currentTimeMillis();
                if (snap.version() < 0 || now - lastVersionCheckAt > VERSION_TTL_MS) {
                    reload();
                }
            }
        }
        return snap;
    }

    private void reload() {
        try {
            long v = metaRepo.findByMetaKey("content:version")
                    .map(x -> versionOf(x.getMetaValue())).orElse(0L);
            Snapshot next = new Snapshot(v,
                    kgRepo.findByTypeAndStatus("DISTORTION", (short) 1).stream().map(this::toDistortion)
                            .filter(java.util.Objects::nonNull).toList(),
                    loadStressorMap(),
                    kgRepo.findByTypeAndStatus("PSY_TOPIC", (short) 1).stream().map(this::toPsy)
                            .filter(java.util.Objects::nonNull).toList(),
                    kgRepo.findByTypeAndStatus("COMM_CASE", (short) 1).stream().map(this::toCase)
                            .filter(java.util.Objects::nonNull).toList(),
                    kgRepo.findByTypeAndStatus("STRENGTH_TECH", (short) 1).stream().map(this::toTech)
                            .filter(java.util.Objects::nonNull).toList(),
                    sceneRepo.findByStatusOrderByCodeAsc((short) 1).stream().map(this::toScene)
                            .filter(java.util.Objects::nonNull).toList(),
                    exerciseRepo.findByStatusOrderByIdAsc((short) 1).stream().map(this::toExercise)
                            .filter(java.util.Objects::nonNull).toList());
            snap = next;
        } catch (Exception e) {
            log.warn("content reload failed, keep previous snapshot", e);
        } finally {
            lastVersionCheckAt = System.currentTimeMillis();
        }
    }

    private static long versionOf(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private JsonNode payload(KgNodeEntity e) throws Exception {
        return mapper.readTree(e.getPayloadJson());
    }

    private DistortionEntry toDistortion(KgNodeEntity e) {
        try {
            JsonNode p = payload(e);
            return new DistortionEntry(e.getCode(), e.getName(),
                    p.path("definition").asText(), p.path("typicalSignature").asText(),
                    strList(p.path("socraticTemplates")), strList(p.path("emotions")));
        } catch (Exception ex) {
            log.warn("bad kg_node payload: {}", e.getCode(), ex);
            return null;
        }
    }

    private PsyEntry toPsy(KgNodeEntity e) {
        try {
            JsonNode p = payload(e);
            return new PsyEntry(e.getCode(), e.getName(), p.path("summary").asText(),
                    p.path("microAction").asText(), strList(p.path("aboutTags")),
                    p.path("readingSec").asInt(40));
        } catch (Exception ex) {
            log.warn("bad kg_node payload: {}", e.getCode(), ex);
            return null;
        }
    }

    private CaseEntry toCase(KgNodeEntity e) {
        try {
            JsonNode p = payload(e);
            return new CaseEntry(e.getCode(), e.getName(), p.path("scene").asText(),
                    p.path("situation").asText(), p.path("unhelpful").asText(), p.path("helpful").asText(),
                    strList(p.path("distortionRefs")), strList(p.path("techniqueRefs")), strList(p.path("aboutTags")));
        } catch (Exception ex) {
            log.warn("bad kg_node payload: {}", e.getCode(), ex);
            return null;
        }
    }

    private TechEntry toTech(KgNodeEntity e) {
        try {
            JsonNode p = payload(e);
            return new TechEntry(e.getCode(), e.getName(), p.path("description").asText(),
                    p.path("whenToUse").asText(), strList(p.path("emotions")), strList(p.path("steps")));
        } catch (Exception ex) {
            log.warn("bad kg_node payload: {}", e.getCode(), ex);
            return null;
        }
    }

    private SceneCard toScene(SceneCardEntity e) {
        try {
            JsonNode p = mapper.readTree(e.getPersonaJson());
            JsonNode persona = p.path("persona");
            Map<String, String> opening = mapper.convertValue(p.path("openingLines"),
                    new TypeReference<>() {});
            return new SceneCard(e.getCode(), e.getTitle(), e.getDescription(),
                    p.path("npcName").asText(), p.path("relation").asText(),
                    csv(e.getDifficulties()),
                    new SceneCard.Persona(persona.path("motivation").asText(), persona.path("bottomLine").asText(),
                            persona.path("triggers").asText(), persona.path("style").asText()),
                    strList(mapper.readTree(e.getGoalDimensions())),
                    e.getMaxTurns(), opening, csv(e.getTags()), csv(e.getRecommendedFor()));
        } catch (Exception ex) {
            log.warn("bad scene_card row: {}", e.getCode(), ex);
            return null;
        }
    }

    private Exercise toExercise(ExerciseLibraryEntity e) {
        try {
            return new Exercise(e.getCode(), e.getName(),
                    strList(mapper.readTree(e.getApplyEmotions())),
                    mapper.readValue(e.getStepsJson(), new TypeReference<>() {}),
                    e.getDurationMin().intValue());
        } catch (Exception ex) {
            log.warn("bad exercise row: {}", e.getCode(), ex);
            return null;
        }
    }

    private Map<String, String> loadStressorMap() {
        return metaRepo.findByMetaKey("kg:stressor_map")
                .map(x -> {
                    try {
                        return mapper.<Map<String, String>>readValue(x.getMetaValue(), new TypeReference<>() {});
                    } catch (Exception e) {
                        return Map.<String, String>of();
                    }
                }).orElseGet(Map::of);
    }

    private List<String> strList(JsonNode arr) {
        if (!arr.isArray()) return List.of();
        return java.util.stream.StreamSupport.stream(arr.spliterator(), false)
                .map(JsonNode::asText).toList();
    }

    private List<String> csv(String s) {
        if (s == null || s.isBlank()) return List.of();
        return List.of(s.split(","));
    }
}
