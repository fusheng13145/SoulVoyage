package com.soulvoyage.agent.support;

import com.soulvoyage.agent.support.Exercise;
import com.soulvoyage.agent.support.ExerciseCatalog;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.exercise.ExerciseRecordEntity;
import com.soulvoyage.domain.exercise.ExerciseRecordRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 练习库与打卡接口（手册 §6.5）：闭集练习目录（服务端配置，非用户数据）+ 属主打卡记录。
 * exerciseId 必须命中闭集（越界即 BAD_PARAMS），与 Agent 侧反向校验同一道墙。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ExerciseController {

    private final ExerciseCatalog catalog;
    private final ExerciseRecordRepository recordRepo;

    public record CheckInReq(@NotBlank String exerciseId, Long planReportId, boolean completed, String feedback) {}

    @GetMapping("/exercises")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(catalog.list().stream().map(e -> Map.<String, Object>of(
                "id", e.id(), "name", e.name(), "durationMin", e.durationMin(),
                "applyEmotions", e.applyEmotions(),
                "steps", e.steps().stream().map(s -> Map.of("step", s.step(), "desc", s.desc())).toList()
        )).toList());
    }

    @PostMapping("/exercise-records")
    public ApiResponse<Map<String, Object>> checkIn(@AuthenticationPrincipal AuthPrincipal p,
                                                    @Valid @RequestBody CheckInReq req) {
        Exercise ex = catalog.list().stream()
                .filter(e -> e.id().equals(req.exerciseId()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.BAD_PARAMS, "未知练习: " + req.exerciseId()));
        ExerciseRecordEntity r = new ExerciseRecordEntity();
        r.setUserId(p.userId());
        r.setExerciseId(ex.dbId());
        r.setPlanReportId(req.planReportId());
        r.setCompleted((short) (req.completed() ? 1 : 0));
        r.setFeedback(req.feedback() == null || req.feedback().isBlank()
                ? null : req.feedback().substring(0, Math.min(255, req.feedback().length())));
        r = recordRepo.save(r);
        return ApiResponse.ok(Map.of("id", r.getId(), "exerciseId", ex.id(), "completed", req.completed()));
    }

    @GetMapping("/exercise-records")
    public ApiResponse<List<Map<String, Object>>> records(
            @AuthenticationPrincipal AuthPrincipal p,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(recordRepo
                .findByUserIdOrderByCreatedAtDescIdDesc(p.userId()).stream()
                .limit(Math.min(limit, 100))
                .map(r -> {
                    String name = catalog.list().stream()
                            .filter(e -> e.dbId().equals(r.getExerciseId()))
                            .map(Exercise::name).findFirst().orElse("练习");
                    return Map.<String, Object>of(
                            "id", r.getId(), "exerciseName", name,
                            "completed", r.getCompleted() == 1,
                            "feedback", r.getFeedback() == null ? "" : r.getFeedback(),
                            "createdAt", r.getCreatedAt() == null ? "" : r.getCreatedAt().toString());
                }).toList());
    }
}
