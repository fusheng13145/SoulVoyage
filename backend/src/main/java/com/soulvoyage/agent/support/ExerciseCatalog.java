package com.soulvoyage.agent.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 练习库目录：classpath exercises.json 建索引（与 SceneCatalog/InMemoryKgService 同构，闭集不外扩）。 */
@Slf4j
@Component
public class ExerciseCatalog {

    private final ObjectMapper mapper;
    private final Map<String, Exercise> byId = new ConcurrentHashMap<>();
    private List<Exercise> all = List.of();

    public ExerciseCatalog(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void load() throws Exception {
        String json = StreamUtils.copyToString(
                new ClassPathResource("exercises/exercises.json").getInputStream(), StandardCharsets.UTF_8);
        all = mapper.readValue(json, new TypeReference<List<Exercise>>() {});
        all.forEach(e -> byId.put(e.id(), e));
        log.info("exercise catalog loaded: {} exercises", all.size());
    }

    public List<Exercise> list() {
        return all;
    }

    public boolean exists(String id) {
        return byId.containsKey(id);
    }

    public Exercise require(String id) {
        Exercise e = byId.get(id);
        if (e == null) throw new BizException(ErrorCode.BAD_PARAMS, "未知练习: " + id);
        return e;
    }
}
