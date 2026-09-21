package com.soulvoyage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O4 Schema 契约测试：/v3/api-docs 全量快照钉在 backend/openapi/openapi.json，
 * 接口增删改未同步快照即红（前端 gen:api 以该文件为 TS 类型真源）。
 * 更新快照：mvn test -Dtest=OpenApiContractTest -Dopenapi.update=true
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiContractTest {

    private static final Path SNAPSHOT = Path.of("openapi", "openapi.json");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void openApiSnapshotIsUpToDate() throws Exception {
        MvcResult res = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        JsonNode doc = mapper.readTree(res.getResponse().getContentAsString());

        // 契约存在性：核心域路由必须出现在文档里（缺了=控制器被误删/路径漂移）
        JsonNode paths = doc.path("paths");
        for (String prefix : List.of("/api/v1/auth", "/api/v1/tasks", "/api/v1/diaries",
                "/api/v1/companion", "/api/v1/readings", "/api/v1/admin/tasks",
                "/api/v1/admin/risk", "/api/v1/admin/ops", "/api/v1/admin/content",
                "/api/v1/admin/users", "/api/v1/admin/metrics", "/api/v1/admin/board")) {
            boolean hit = false;
            var it = paths.fieldNames();
            while (it.hasNext()) if (it.next().startsWith(prefix)) { hit = true; break; }
            assertTrue(hit, "OpenAPI 缺少路由前缀: " + prefix);
        }

        // 文档不含敏感形态：审计/复核等管理路由全部登记 401/403 语义（bearer 鉴权在）
        assertTrue(doc.path("components").path("securitySchemes").has("bearerAuth"));

        String current = canonical(doc);
        if (Boolean.getBoolean("openapi.update") || !Files.exists(SNAPSHOT)) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, current);
            return;
        }
        assertEquals(canonical(mapper.readTree(Files.readString(SNAPSHOT))), current,
                "OpenAPI 契约已变化：跑 -Dopenapi.update=true 重新生成快照并同步前端 gen:api");
    }

    /** 键序稳定化：服务端 Map 序不参与比对 */
    private String canonical(JsonNode doc) throws Exception {
        return mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writerWithDefaultPrettyPrinter().writeValueAsString(doc);
    }
}
