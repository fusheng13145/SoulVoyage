package com.leyon.soul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.domain.crisis.CrisisState;
import com.leyon.soul.domain.task.TaskInstanceRepository;
import com.leyon.soul.orchestrator.PipelineCode;
import com.leyon.soul.orchestrator.pipeline.TaskContext;
import com.leyon.soul.orchestrator.pipeline.TaskDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-04 流水线契约：枚举全覆盖、未知流水线当场 3002、结构缺失当场 2001 且不落任务行。
 * 这些错误此前要等异步步骤执行到一半才暴露，现在必须在提交边界收口。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PipelineContractTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired List<TaskDefinition> definitions;
    @Autowired TaskInstanceRepository tasks;

    @Test
    void everyPipelineCodeHasExactlyOneDefinition() {
        Map<PipelineCode, TaskDefinition> byCode = new EnumMap<>(PipelineCode.class);
        for (TaskDefinition d : definitions) {
            assertNull(byCode.put(d.code(), d), "流水线编码重复注册: " + d.code());
        }
        for (PipelineCode code : PipelineCode.values()) {
            assertNotNull(byCode.get(code), "流水线缺少实现: " + code);
        }
        TaskContext probe = new TaskContext(0L, 0L, CrisisState.NORMAL, mapper.createObjectNode());
        for (TaskDefinition d : definitions) {
            assertFalse(d.steps(probe).isEmpty(), "流水线没有步骤: " + d.code());
        }
    }

    @Test
    void unknownPipelineIsRejectedAtParsing() {
        BizException e = assertThrows(BizException.class, () -> PipelineCode.from("NOPE"));
        assertEquals(ErrorCode.PIPELINE_NOT_ALLOWED.code(), e.getErrorCode().code());
        assertThrows(BizException.class, () -> PipelineCode.from(null));
        assertThrows(BizException.class, () -> PipelineCode.from(" "));
    }

    @Test
    void invalidPayloadFailsBeforeTaskIsPersisted() throws Exception {
        String jwt = register();
        long before = tasks.count();

        MvcResult unknown = postTask(jwt, "{\"pipelineCode\":\"NOPE\",\"payload\":{},\"clientReqId\":\"c-unknown\"}");
        assertEquals(400, unknown.getResponse().getStatus());
        assertEquals(3002, body(unknown).path("code").asInt());

        MvcResult simulate = postTask(jwt,
                "{\"pipelineCode\":\"SIMULATE_PIPELINE\",\"payload\":{},\"clientReqId\":\"c-sim\"}");
        assertEquals(400, simulate.getResponse().getStatus());
        assertEquals(2001, body(simulate).path("code").asInt());

        MvcResult companion = postTask(jwt,
                "{\"pipelineCode\":\"COMPANION_PIPELINE\",\"payload\":{},\"clientReqId\":\"c-comp\"}");
        assertEquals(400, companion.getResponse().getStatus());
        assertEquals(2001, body(companion).path("code").asInt());

        MvcResult letter = postTask(jwt,
                "{\"pipelineCode\":\"GROWTH_LETTER_PIPELINE\",\"payload\":{},\"clientReqId\":\"c-letter\"}");
        assertEquals(400, letter.getResponse().getStatus());
        assertEquals(2001, body(letter).path("code").asInt());

        assertEquals(before, tasks.count(), "校验失败不得留下任务行");
    }

    private MvcResult postTask(String jwt, String json) throws Exception {
        return mvc.perform(post("/api/v1/tasks")
                .header("Authorization", "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    private JsonNode body(MvcResult res) throws Exception {
        return mapper.readTree(res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String register() throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "username", "pipe_" + System.nanoTime(),
                                "password", "Passw0rd!2026",
                                "nickname", "流水线契约"))))
                .andExpect(status().isOk())
                .andReturn();
        return body(res).path("data").path("accessToken").asText();
    }
}
