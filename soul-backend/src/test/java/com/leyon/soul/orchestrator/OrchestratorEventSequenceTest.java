package com.leyon.soul.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leyon.soul.crypto.CryptoService;
import com.leyon.soul.domain.task.AgentMessageRepository;
import com.leyon.soul.domain.task.TaskInstanceEntity;
import com.leyon.soul.domain.task.TaskInstanceRepository;
import com.leyon.soul.domain.task.TaskStepLogRepository;
import com.leyon.soul.domain.user.UserRepository;
import com.leyon.soul.orchestrator.agent.Agent;
import com.leyon.soul.orchestrator.agent.OutputInvalidException;
import com.leyon.soul.orchestrator.pipeline.PipelineRegistry;
import com.leyon.soul.orchestrator.pipeline.StepSpec;
import com.leyon.soul.orchestrator.pipeline.TaskDefinition;
import com.leyon.soul.orchestrator.sse.TaskEventBus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * B-03 事件契约：拆分后必须逐字保持 SSE 事件名、顺序与状态序列。
 * 用录制总线替代真实投递，覆盖 SUCCESS / PARTIAL_SUCCESS / CANCELLED 三条路径。
 */
class OrchestratorEventSequenceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final PipelineRegistry pipelines = mock(PipelineRegistry.class);
    private final AgentRegistry agents = mock(AgentRegistry.class);
    private final TaskInstanceRepository taskRepo = mock(TaskInstanceRepository.class);
    private final TaskStepLogRepository stepRepo = mock(TaskStepLogRepository.class);
    private final AgentMessageRepository msgRepo = mock(AgentMessageRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    private final Agent agent = mock(Agent.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<TaskSubmissionHook> hooks = mock(ObjectProvider.class);

    private RecordingBus bus;
    private TaskSubmissionService submission;
    private TaskExecutionService execution;

    static class RecordingBus extends TaskEventBus {
        final List<String> names = new ArrayList<>();
        final List<Map<String, Object>> payloads = new ArrayList<>();

        RecordingBus(ObjectMapper mapper) {
            super(mapper);
        }

        @Override
        public void publish(String taskNo, String event, Map<String, Object> data) {
            names.add(event);
            payloads.add(data);
        }
    }

    @BeforeEach
    void setUp() {
        bus = new RecordingBus(mapper);
        TaskEventPublisher publisher = new TaskEventPublisher(bus);
        TaskMessageCodec codec = new TaskMessageCodec(msgRepo, crypto, mapper);
        TaskStepRunner runner = new TaskStepRunner(agents, stepRepo, msgRepo, crypto, mapper, registry, publisher);
        execution = new TaskExecutionService(pipelines, taskRepo, stepRepo, userRepo, registry,
                runner, publisher, dispatcher, codec, mapper);
        submission = new TaskSubmissionService(pipelines, taskRepo,
                new TaskGateService(taskRepo, userRepo, registry),
                publisher, dispatcher, execution, mapper, hooks);
        lenient().when(hooks.orderedStream()).thenReturn(java.util.stream.Stream.empty());

        TaskDefinition definition = mock(TaskDefinition.class);
        lenient().when(pipelines.require(PipelineCode.DIARY_PIPELINE)).thenReturn(definition);
        lenient().when(pipelines.require("DIARY_PIPELINE")).thenReturn(definition);
        lenient().when(definition.steps(any())).thenReturn(List.of(
                new StepSpec("emotion", AgentCode.EMOTION, "emotion_result.json")));

        lenient().when(agents.require(AgentCode.EMOTION)).thenReturn(agent);
        lenient().when(userRepo.findById(anyLong())).thenReturn(Optional.empty());
        lenient().when(taskRepo.countActive(anyLong())).thenReturn(0L);
        lenient().when(taskRepo.findByUserIdAndClientReqId(anyLong(), anyString())).thenReturn(Optional.empty());
        lenient().when(taskRepo.save(any(TaskInstanceEntity.class))).thenAnswer(inv -> {
            TaskInstanceEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(1L);
            persisted = e;
            return e;
        });
        lenient().when(taskRepo.findById(1L)).thenAnswer(inv -> Optional.ofNullable(persisted));
        lenient().when(msgRepo.findByTaskIdOrderByStepSeqAscIdAsc(anyLong())).thenReturn(List.of());
        lenient().when(crypto.encryptUserField(anyLong(), anyString())).thenReturn(new byte[]{1});
        lenient().doAnswer(inv -> {
            if (inline) ((Runnable) inv.getArgument(0)).run();   // 同步执行，便于断言
            return null;
        }).when(dispatcher).dispatch(any());
    }

    private TaskInstanceEntity submitted;
    private TaskInstanceEntity persisted;
    private boolean inline = true;

    private void submit() {
        ObjectNode input = mapper.createObjectNode().put("diaryText", "今天有点累");
        submitted = submission.submit(7L, "DIARY_PIPELINE", input, null);
    }

    @Test
    void successPublishesExactSequence() {
        ObjectNode out = mapper.createObjectNode().put("mood", "CALM");
        when(agent.run(any(), any())).thenReturn(out);

        submit();

        assertEquals(List.of("task_created", "task_status", "step_started",
                "middle_result", "task_status", "done"), bus.names);
        assertEquals("RUNNING", bus.payloads.get(1).get("status"));
        assertEquals("SUCCESS", bus.payloads.get(4).get("status"));
        assertEquals("SUCCESS", bus.payloads.get(5).get("status"));
        assertEquals(out, bus.payloads.get(5).get("payload"));
        assertEquals("SUCCESS", submitted.getStatus());
    }

    @Test
    void invalidOutputDegradesToPartialSuccess() {
        when(agent.run(any(), any())).thenThrow(new OutputInvalidException("输出越界"));

        submit();

        assertEquals(List.of("task_created", "task_status", "step_started",
                "step_failed", "task_status", "done"), bus.names);
        assertEquals("PARTIAL_SUCCESS", bus.payloads.get(4).get("status"));
        assertEquals("输出越界", bus.payloads.get(3).get("reason"));
        assertEquals("PARTIAL_SUCCESS", submitted.getStatus());
    }

    @Test
    void cancelPublishesCancelledAndStopsFurtherTransitions() {
        inline = false;   // 不自动执行，保留 PENDING 以便取消
        submit();

        execution.cancel(submitted);

        assertEquals(List.of("task_created", "task_status", "done"), bus.names);
        assertEquals("CANCELLED", bus.payloads.get(1).get("status"));
        assertEquals("CANCELLED", submitted.getStatus());
        assertThrows(IllegalStateException.class,
                () -> TaskStateMachine.requireTransition(TaskStatus.CANCELLED, TaskStatus.SUCCESS));
    }
}
