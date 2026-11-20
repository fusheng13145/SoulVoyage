package com.leyon.soul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.soul.domain.task.TaskInstanceEntity;
import com.leyon.soul.domain.task.TaskInstanceRepository;
import com.leyon.soul.orchestrator.OrchestratorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * H-01 任务中心契约：步骤时间线属主可读、越权 403；重试仅 FAILED 可入且复用既有续跑。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserTaskCenterTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired OrchestratorService orchestrator;
    @Autowired TaskInstanceRepository tasks;

    @Test
    void stepsAreOwnerOnlyAndRetryRequiresFailed() throws Exception {
        String owner = register("tc_owner");
        String other = register("tc_other");
        String taskNo = submitDiary(owner);
        awaitTerminal(taskNo);

        // 属主可读时间线
        MvcResult steps = mvc.perform(get("/api/v1/tasks/" + taskNo + "/steps")
                .header("Authorization", "Bearer " + owner)).andReturn();
        assertEquals(200, steps.getResponse().getStatus());
        JsonNode items = body(steps).path("data");
        assertTrue(items.isArray() && items.size() > 0, "步骤时间线不应为空");
        assertTrue(items.get(0).has("stepSeq") && items.get(0).has("status") && items.get(0).has("errorMsg"));

        // 越权读取 403
        MvcResult forbidden = mvc.perform(get("/api/v1/tasks/" + taskNo + "/steps")
                .header("Authorization", "Bearer " + other)).andReturn();
        assertEquals(403, forbidden.getResponse().getStatus());
        assertEquals(1002, body(forbidden).path("code").asInt());

        // 非 FAILED 不可重试
        MvcResult notAllowed = retry(owner, taskNo);
        assertEquals(400, notAllowed.getResponse().getStatus());
        assertEquals(2001, body(notAllowed).path("code").asInt());

        // 构造 FAILED 后重试入口可用（续跑由既有执行服务负责）
        TaskInstanceEntity t = orchestrator.byNo(taskNo);
        t.setStatus("FAILED");
        tasks.save(t);
        MvcResult retried = retry(owner, taskNo);
        assertEquals(200, retried.getResponse().getStatus());
        assertEquals("RUNNING", body(retried).path("data").path("status").asText());

        // 异步执行收口（避免测试上下文关闭时仍在跑）
        Thread.sleep(300);
        awaitTerminal(taskNo);
    }

    private MvcResult retry(String jwt, String taskNo) throws Exception {
        return mvc.perform(post("/api/v1/tasks/" + taskNo + "/retry")
                .header("Authorization", "Bearer " + jwt)).andReturn();
    }

    private String submitDiary(String jwt) throws Exception {
        String json = mapper.writeValueAsString(Map.of(
                "pipelineCode", "DIARY_PIPELINE",
                "payload", Map.of("diaryText", "今天有点累，想早点睡。"),
                "clientReqId", "h01-" + System.nanoTime()));
        MvcResult res = mvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
        assertEquals(202, res.getResponse().getStatus());
        return body(res).path("data").path("taskNo").asText();
    }

    private void awaitTerminal(String taskNo) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            String s = orchestrator.byNo(taskNo).getStatus();
            if (s.equals("SUCCESS") || s.equals("FAILED") || s.equals("PARTIAL_SUCCESS")) return;
            Thread.sleep(100);
        }
        fail("任务未在 15s 内结束: " + taskNo);
    }

    private JsonNode body(MvcResult res) throws Exception {
        return mapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String register(String prefix) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "username", prefix + "_" + System.nanoTime(),
                                "password", "SvFixture!2026",
                                "nickname", "任务中心"))))
                .andReturn();
        assertEquals(200, res.getResponse().getStatus());
        return body(res).path("data").path("accessToken").asText();
    }
}
