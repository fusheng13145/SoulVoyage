package com.soulvoyage.agent.simulate;

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

/** 场景卡目录：启动时加载 classpath JSON 建索引（与 InMemoryKgService 同构）。 */
@Slf4j
@Component
public class SceneCatalog {

    private final ObjectMapper mapper;
    private final Map<String, SceneCard> byCode = new ConcurrentHashMap<>();
    private List<SceneCard> all = List.of();

    public SceneCatalog(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void load() throws Exception {
        String json = StreamUtils.copyToString(
                new ClassPathResource("scenes/scenes.json").getInputStream(), StandardCharsets.UTF_8);
        all = mapper.readValue(json, new TypeReference<List<SceneCard>>() {});
        all.forEach(c -> byCode.put(c.code(), c));
        log.info("scene catalog loaded: {} cards", all.size());
    }

    public List<SceneCard> list() {
        return all;
    }

    public SceneCard require(String code) {
        SceneCard c = byCode.get(code);
        if (c == null) throw new BizException(ErrorCode.BAD_PARAMS, "未知场景: " + code);
        return c;
    }
}
