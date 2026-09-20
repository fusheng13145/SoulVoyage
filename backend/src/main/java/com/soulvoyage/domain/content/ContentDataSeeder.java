package com.soulvoyage.domain.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.stream.StreamSupport;

/**
 * 内容种子（N4 前置）：表为空时从 classpath JSON 导入，之后 JSON 退役为种子源、DB 为运行时真源。
 * 覆盖 kg_node（四类）、exercise_library（code 统一 ex_*）、scene_card、content_meta（stressorMap + content:version）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContentDataSeeder implements ApplicationRunner {

    private static final short ON = 1;

    private final KgNodeRepository kgRepo;
    private final ContentMetaRepository metaRepo;
    private final ExerciseLibraryRepository exerciseRepo;
    private final SceneCardRepository sceneRepo;
    private final ContentStore store;
    private final ObjectMapper mapper;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        seedKg();
        seedExercises();
        seedScenes();
        ensureVersion();
        store.bump();   // 种子落库后抬版本，确保快照与 DB 对齐（若轮询先读到空表）
    }

    private JsonNode readJson(String path) throws Exception {
        return mapper.readTree(new String(
                new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private void seedKg() throws Exception {
        if (kgRepo.count() > 0) return;
        int n = 0;
        JsonNode dist = readJson("kg/cognitive_distortions.json");
        for (JsonNode d : dist.path("distortions"))
            n += insertNode("DISTORTION", d.get("kgNodeId").asText(), d.get("name").asText(), d);
        for (JsonNode p : readJson("kg/psy_topics.json").path("topics"))
            n += insertNode("PSY_TOPIC", p.get("kgNodeId").asText(), p.get("title").asText(), p);
        for (JsonNode c : readJson("kg/comm_cases.json").path("cases"))
            n += insertNode("COMM_CASE", c.get("kgNodeId").asText(), c.get("title").asText(), c);
        for (JsonNode t : readJson("kg/strength_techniques.json").path("techniques"))
            n += insertNode("STRENGTH_TECH", t.get("kgNodeId").asText(), t.get("name").asText(), t);
        if (metaRepo.findByMetaKey("kg:stressor_map").isEmpty())
            metaRepo.save(meta("kg:stressor_map", mapper.writeValueAsString(dist.path("stressorMap"))));
        log.info("content seed: kg_node={} rows", n);
    }

    private int insertNode(String type, String code, String name, JsonNode payload) {
        KgNodeEntity e = new KgNodeEntity();
        e.setType(type);
        e.setCode(code);
        e.setName(name);
        e.setPayloadJson(payload.toString());
        e.setStatus(ON);
        kgRepo.save(e);
        return 1;
    }

    private void seedExercises() throws Exception {
        if (exerciseRepo.count() > 0) return;
        JsonNode items = readJson("exercises/exercises.json");
        for (JsonNode x : items) {
            ExerciseLibraryEntity e = new ExerciseLibraryEntity();
            e.setCode(x.get("id").asText());               // ex_*：与 Agent 闭集 id 同名，废弃 EX_*/dbId 双轨
            e.setName(x.get("name").asText());
            e.setApplyEmotions(x.path("applyEmotions").toString());
            e.setStepsJson(x.path("steps").toString());
            e.setDurationMin((short) x.path("durationMin").asInt(5));
            e.setStatus(ON);
            exerciseRepo.save(e);
        }
        log.info("content seed: exercise_library={} rows", items.size());
    }

    private void seedScenes() throws Exception {
        if (sceneRepo.count() > 0) return;
        JsonNode scenes = readJson("scenes/scenes.json");
        int n = 0;
        for (JsonNode c : scenes) {
            SceneCardEntity e = new SceneCardEntity();
            e.setCode(c.get("code").asText());
            e.setTitle(c.get("title").asText());
            e.setDescription(c.get("description").asText());
            e.setDifficulties(String.join(",", StreamSupport
                    .stream(c.path("difficulties").spliterator(), false).map(JsonNode::asText).toList()));
            ObjectNode persona = mapper.createObjectNode();
            persona.put("npcName", c.path("npcName").asText());
            persona.put("relation", c.path("relation").asText());
            persona.set("persona", c.path("persona"));
            persona.set("openingLines", c.path("openingLines"));
            e.setPersonaJson(persona.toString());
            e.setGoalDimensions(c.path("goalDimensions").toString());
            e.setMaxTurns(c.path("maxTurns").asInt(20));
            if (c.hasNonNull("tags")) e.setTags(String.join(",", StreamSupport
                    .stream(c.path("tags").spliterator(), false).map(JsonNode::asText).toList()));
            if (c.hasNonNull("recommendedFor")) e.setRecommendedFor(String.join(",", StreamSupport
                    .stream(c.path("recommendedFor").spliterator(), false).map(JsonNode::asText).toList()));
            e.setStatus(ON);
            sceneRepo.save(e);
            n++;
        }
        log.info("content seed: scene_card={} rows", n);
    }

    private void ensureVersion() {
        if (metaRepo.findByMetaKey("content:version").isEmpty())
            metaRepo.save(meta("content:version", "1"));
    }

    private ContentMetaEntity meta(String key, String value) {
        ContentMetaEntity m = new ContentMetaEntity();
        m.setMetaKey(key);
        m.setMetaValue(value);
        return m;
    }
}
