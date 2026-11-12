package com.leyon.soul.domain.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** H-04 发布前 schema 校验：四类 KG、场景、练习的合法/非法边界。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentSchemaValidatorTest {

    @Mock ContentStore store;

    private final ObjectMapper mapper = new ObjectMapper();
    private ContentSchemaValidator validator;

    @BeforeEach
    void setUp() {
        when(store.stressorDefinitions()).thenReturn(Map.of(
                "人际冲突", new ContentStore.StressorEntry("人际冲突", "x", "y"),
                "学业压力", new ContentStore.StressorEntry("学业压力", "x", "y")));
        validator = new ContentSchemaValidator(store, mapper);
    }

    private ObjectNode obj(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    @DisplayName("KG：四类合法样例通过，status 0 归一化为下架")
    void acceptsValidKgTypes() {
        assertTrue(validator.validate(ContentType.KG, "cd_test", obj("""
                {"type":"DISTORTION","name":"灾难化","status":0,
                 "payload":{"definition":"把风险放大","socraticTemplates":["最坏结果是什么？"],
                            "typicalSignature":"完了","emotions":["焦虑"]}}
                """)).valid());
        assertTrue(validator.validate(ContentType.KG, "psy_test", obj("""
                {"type":"PSY_TOPIC","name":"情绪是什么","status":1,
                 "payload":{"summary":"s","microAction":"m","aboutTags":["焦虑"],"readingSec":40}}
                """)).valid());
        assertTrue(validator.validate(ContentType.KG, "cc_test", obj("""
                {"type":"COMM_CASE","name":"案例","status":1,
                 "payload":{"situation":"s","unhelpful":"u","helpful":"h",
                            "distortionRefs":["cd_test"],"techniqueRefs":["st_test"],"aboutTags":["人际冲突"]}}
                """)).valid());
        assertTrue(validator.validate(ContentType.KG, "st_test", obj("""
                {"type":"STRENGTH_TECH","name":"技巧","status":1,
                 "payload":{"description":"d","whenToUse":"w","steps":["一步"],"emotions":["焦虑"]}}
                """)).valid());

        JsonNode normalized = validator.validate(ContentType.KG, "cd_test", obj("""
                {"type":"DISTORTION","name":"灾难化","status":0,
                 "payload":{"definition":"d","socraticTemplates":["q"]}}
                """)).normalized();
        assertEquals(2, normalized.path("status").asInt());
    }

    @Test
    @DisplayName("KG：缺字段、前缀不符、超长与非法 status 被阻断")
    void rejectsInvalidKg() {
        assertFalse(validator.validate(ContentType.KG, "psy_bad", obj("""
                {"type":"DISTORTION","name":"n","payload":{"definition":"d","socraticTemplates":["q"]}}
                """)).valid(), "prefix 与 type 不一致");
        assertFalse(validator.validate(ContentType.KG, "cd_bad", obj("""
                {"type":"DISTORTION","name":"n","payload":{"definition":"d"}}
                """)).valid(), "缺 socraticTemplates");
        assertFalse(validator.validate(ContentType.KG, "cd_bad", obj("""
                {"type":"DISTORTION","name":"n","payload":{"definition":"d","socraticTemplates":["q"]},
                 "status":9}
                """)).valid(), "非法 status");
        assertFalse(validator.validate(ContentType.KG, "cd_bad", obj("""
                {"type":"DISTORTION","name":"n","payload":{"definition":"d","socraticTemplates":["q"]},
                 "status":"on"}
                """)).valid(), "status 非数字");
    }

    @Test
    @DisplayName("场景：合法样例通过；难度/维度/开场白/压力源闭集越界被阻断")
    void validatesScene() {
        String valid = """
                {"title":"宿舍冲突","description":"和室友谈边界","difficulties":["MILD","NORMAL"],
                 "npcName":"小磊","relation":"室友",
                 "persona":{"motivation":"m","bottomLine":"b","triggers":"t","style":"s"},
                 "goalDimensions":["LISTEN","BOUNDARY"],"maxTurns":20,
                 "openingLines":{"MILD":"轻","NORMAL":"中"},
                 "tags":["人际冲突"],"recommendedFor":["人际冲突"],"status":1}
                """;
        assertTrue(validator.validate(ContentType.SCENE, "H04_SCENE", obj(valid)).valid());

        assertFalse(validator.validate(ContentType.SCENE, "h04_scene", obj(valid)).valid(), "code 小写");
        assertFalse(validator.validate(ContentType.SCENE, "H04_SCENE", obj(valid
                .replace("\"MILD\",\"NORMAL\"", "\"EASY\""))).valid(), "未知难度");
        assertFalse(validator.validate(ContentType.SCENE, "H04_SCENE", obj(valid
                .replace("\"openingLines\":{\"MILD\":\"轻\",\"NORMAL\":\"中\"}",
                        "\"openingLines\":{\"MILD\":\"轻\"}"))).valid(), "缺 NORMAL 开场白");
        assertFalse(validator.validate(ContentType.SCENE, "H04_SCENE", obj(valid
                .replace("\"recommendedFor\":[\"人际冲突\"]", "\"recommendedFor\":[\"不存在的压力源\"]")))
                .valid(), "压力源闭集越界");
        assertFalse(validator.validate(ContentType.SCENE, "H04_SCENE", obj(valid
                .replace("\"maxTurns\":20", "\"maxTurns\":80"))).valid(), "maxTurns 越界");
    }

    @Test
    @DisplayName("练习：合法样例通过；steps/duration/code 边界被阻断")
    void validatesExercise() {
        String valid = """
                {"name":"呼吸","applyEmotions":["ANXIETY"],
                 "steps":[{"step":"吸气","desc":"4 秒"}],"durationMin":5,"status":1}
                """;
        assertTrue(validator.validate(ContentType.EXERCISE, "ex_h04_test", obj(valid)).valid());

        assertFalse(validator.validate(ContentType.EXERCISE, "EX_BAD", obj(valid)).valid(), "code 大写");
        assertFalse(validator.validate(ContentType.EXERCISE, "ex_h04_test", obj(valid
                .replace("{\"step\":\"吸气\",\"desc\":\"4 秒\"}", "{\"step\":\"吸气\"}")))
                .valid(), "缺 desc");
        assertFalse(validator.validate(ContentType.EXERCISE, "ex_h04_test", obj(valid
                .replace("\"durationMin\":5", "\"durationMin\":0"))).valid(), "时长越界");
        assertFalse(validator.validate(ContentType.EXERCISE, "ex_h04_test", obj(valid
                .replace("[\"ANXIETY\"]", "[]"))).valid(), "applyEmotions 为空");
    }

    @Test
    @DisplayName("非对象输入与 code 为空被阻断")
    void rejectsNonObjectAndMissingCode() {
        assertFalse(validator.validate(ContentType.KG, "cd_x", mapper.createArrayNode()).valid());
        assertFalse(validator.validate(ContentType.KG, "", obj("{}")).valid());
        assertEquals(1, validator.codeErrors(ContentType.EXERCISE, "BAD").size());
    }
}
