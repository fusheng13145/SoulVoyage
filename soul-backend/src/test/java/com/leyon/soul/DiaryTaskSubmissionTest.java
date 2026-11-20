package com.leyon.soul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.soul.domain.diary.DiaryService;
import com.leyon.soul.domain.diary.DiarySubmissionHook;
import com.leyon.soul.domain.task.TaskInstanceEntity;
import com.leyon.soul.domain.task.TaskInstanceRepository;
import com.leyon.soul.domain.user.UserRepository;
import com.leyon.soul.orchestrator.PipelineCode;
import com.leyon.soul.orchestrator.TaskDispatcher;
import com.leyon.soul.orchestrator.TaskEventPublisher;
import com.leyon.soul.orchestrator.TaskExecutionService;
import com.leyon.soul.orchestrator.TaskGateService;
import com.leyon.soul.orchestrator.TaskSubmissionHook;
import com.leyon.soul.orchestrator.TaskSubmissionService;
import com.leyon.soul.orchestrator.pipeline.PipelineRegistry;
import com.leyon.soul.orchestrator.pipeline.TaskDefinition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * B-11：日记落库从 Controller 迁到提交钩子，且必须发生在异步派发之前。
 * 同时验证 clientReqId 幂等重放不会重复触发钩子。
 */
class DiaryTaskSubmissionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PipelineRegistry pipelines = mock(PipelineRegistry.class);
    private final TaskDefinition definition = mock(TaskDefinition.class);
    private final TaskInstanceRepository taskRepo = mock(TaskInstanceRepository.class);
    private final TaskEventPublisher events = mock(TaskEventPublisher.class);
    private final TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    private final TaskExecutionService execution = mock(TaskExecutionService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<TaskSubmissionHook> hooks = mock(ObjectProvider.class);

    private final List<String> order = new ArrayList<>();
    private TaskInstanceEntity hookedTask;

    private final TaskSubmissionHook hook = new TaskSubmissionHook() {
        @Override
        public PipelineCode pipeline() {
            return PipelineCode.DIARY_PIPELINE;
        }

        @Override
        public void afterSubmit(long userId, JsonNode payload, TaskInstanceEntity task) {
            order.add("hook");
            hookedTask = task;
        }
    };

    private TaskSubmissionService service;

    @BeforeEach
    void setUp() {
        service = new TaskSubmissionService(pipelines, taskRepo,
                new TaskGateService(taskRepo, mock(UserRepository.class), new SimpleMeterRegistry()),
                events, dispatcher, execution, mapper, hooks);
        when(pipelines.require(PipelineCode.DIARY_PIPELINE)).thenReturn(definition);
        when(taskRepo.countActive(anyLong())).thenReturn(0L);
        when(taskRepo.save(any(TaskInstanceEntity.class))).thenAnswer(inv -> {
            TaskInstanceEntity e = inv.getArgument(0);
            e.setId(9L);
            return e;
        });
        when(hooks.orderedStream()).thenReturn(java.util.stream.Stream.of(hook));
        doAnswer(inv -> {
            order.add("dispatch");
            return null;
        }).when(dispatcher).dispatch(any());
    }

    @Test
    void hookRunsBeforeDispatch() {
        var payload = mapper.createObjectNode().put("diaryText", "今天有点累");

        TaskInstanceEntity t = service.submit(1L, "DIARY_PIPELINE", payload, "req-1");

        assertEquals(List.of("hook", "dispatch"), order);
        assertSame(t, hookedTask);
        assertNotNull(hookedTask.getId(), "钩子拿到的任务应已落库并带主键");
        verify(execution, never()).run(anyLong(), any());
    }

    @Test
    void idempotentReplaySkipsHookAndDispatch() {
        TaskInstanceEntity existing = new TaskInstanceEntity();
        existing.setId(3L);
        existing.setTaskNo("T-existing");
        when(taskRepo.findByUserIdAndClientReqId(1L, "req-1")).thenReturn(Optional.of(existing));

        TaskInstanceEntity t = service.submit(1L, "DIARY_PIPELINE",
                mapper.createObjectNode().put("diaryText", "重复提交"), "req-1");

        assertSame(existing, t);
        assertTrue(order.isEmpty(), "幂等重放不得再次写日记或派发");
    }

    @Test
    void diaryHookOnlyDelegatesForDiaryPayload() {
        DiaryService diary = mock(DiaryService.class);
        DiarySubmissionHook diaryHook = new DiarySubmissionHook(diary);
        TaskInstanceEntity task = new TaskInstanceEntity();
        JsonNode payload = mapper.createObjectNode().put("diaryText", "x");

        assertEquals(PipelineCode.DIARY_PIPELINE, diaryHook.pipeline());
        diaryHook.afterSubmit(1L, payload, task);
        verify(diary).attachTask(1L, payload, task);

        diaryHook.afterSubmit(1L, null, task);
        verify(diary, times(1)).attachTask(anyLong(), any(), any());
    }
}
