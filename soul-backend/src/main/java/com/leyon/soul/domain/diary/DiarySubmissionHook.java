package com.leyon.soul.domain.diary;

import com.fasterxml.jackson.databind.JsonNode;
import com.leyon.soul.domain.task.TaskInstanceEntity;
import com.leyon.soul.orchestrator.PipelineCode;
import com.leyon.soul.orchestrator.TaskSubmissionHook;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 日记流水线的提交后置：提交即同步落日记（clientReqId 幂等重放不会重复写）。
 * 从 TaskController 迁出（B-11），传输层不再依赖 domain.diary。
 */
@Component
@RequiredArgsConstructor
public class DiarySubmissionHook implements TaskSubmissionHook {

    private final DiaryService diaryService;

    @Override
    public PipelineCode pipeline() {
        return PipelineCode.DIARY_PIPELINE;
    }

    @Override
    public void afterSubmit(long userId, JsonNode payload, TaskInstanceEntity task) {
        if (payload != null) diaryService.attachTask(userId, payload, task);
    }
}
