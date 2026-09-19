package com.soulvoyage.agent.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.domain.achievement.AchievementService;
import com.soulvoyage.domain.emotion.EmotionCatalog;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.exercise.ExerciseRecordEntity;
import com.soulvoyage.domain.exercise.ExerciseRecordRepository;
import com.soulvoyage.domain.plan.PlanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 练习库 + C4 沉浸跟练产出（手册 §6.5 / 下篇·C4）：
 * 闭集练习目录；跟练打卡升级——同日同练习覆盖式、挂 G4 计划回写 plan_item、
 * 行为激活 1-5 星即时复评写 SELF_RATING 轨迹点；认知书写三栏提交走 COGNITIVE_PIPELINE 生成追问。
 * exerciseId 必须命中闭集（越界即 BAD_PARAMS），与 Agent 侧反向校验同一道墙。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class ExerciseController {

    private final ExerciseCatalog catalog;
    private final ExerciseRecordRepository recordRepo;
    private final EmotionTrajectoryRepository trajRepo;
    private final PlanService plans;
    private final AchievementService achievements;
    private final BusinessCalendar cal;
    private final ObjectMapper mapper;
    private final com.soulvoyage.orchestrator.OrchestratorService orchestrator;

    public record CheckInReq(@NotBlank String exerciseId, Long planReportId, boolean completed,
                             String feedback, String planId, Integer planItemSeq,
                             LocalDate scheduledDate, Integer durationActual, Integer ratingStar) {}

    public record CognitiveReq(@NotBlank String situation, @NotBlank String autoThought,
                               @NotBlank String alternativeThought, String planId,
                               Integer planItemSeq, Integer durationActual) {}

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
        Exercise ex = catalog.require(req.exerciseId());
        LocalDate day = req.scheduledDate() == null ? cal.today() : req.scheduledDate();
        ExerciseRecordEntity r = recordRepo
                .findByUserIdAndExerciseIdAndCheckDate(p.userId(), ex.dbId(), day)
                .orElseGet(() -> {
                    ExerciseRecordEntity n = new ExerciseRecordEntity();
                    n.setUserId(p.userId());
                    n.setExerciseId(ex.dbId());
                    n.setCheckDate(day);
                    return n;
                });
        Long planDbId = parsePlanId(req.planId());
        if (planDbId != null) {
            r.setPlanId(planDbId);
            r.setPlanItemSeq(req.planItemSeq());
        }
        r.setPlanReportId(req.planReportId() == null ? r.getPlanReportId() : req.planReportId());
        r.setCompleted((short) (req.completed() ? 1 : 0));
        if (req.feedback() != null && !req.feedback().isBlank()) {
            r.setFeedback(req.feedback().substring(0, Math.min(255, req.feedback().length())));
        }
        if (req.durationActual() != null && req.durationActual() >= 0) {
            r.setDurationActual(req.durationActual());
        }
        r = recordRepo.save(r);

        if (req.completed()) {
            afterCompletion(p.userId(), planDbId, req.planItemSeq(), req.ratingStar(), day);
        }
        return ApiResponse.ok(Map.of("id", r.getId(), "exerciseId", ex.id(), "completed", true));
    }

    /** 认知书写三栏 → COGNITIVE_PIPELINE（EMOTION→TRACE 追问→RISK_ARCHIVE），并自动完成该练习 */
    @PostMapping("/exercises/cognitive-writing")
    public ApiResponse<Map<String, String>> cognitive(@AuthenticationPrincipal AuthPrincipal p,
                                                      @Valid @RequestBody CognitiveReq req) {
        String text = "情境：" + req.situation() + "\n自动想法：" + req.autoThought()
                + "\n替代想法：" + req.alternativeThought();
        var input = mapper.createObjectNode();
        input.put("diaryText", text);
        input.put("sourceType", "EXERCISE");
        input.put("recordDate", cal.today().toString());
        String taskNo = orchestrator.submit(p.userId(), "COGNITIVE_PIPELINE", input, null).getTaskNo();

        Exercise cbt = catalog.require("ex_cbt_write");
        LocalDate day = cal.today();
        ExerciseRecordEntity r = recordRepo
                .findByUserIdAndExerciseIdAndCheckDate(p.userId(), cbt.dbId(), day)
                .orElseGet(() -> {
                    ExerciseRecordEntity n = new ExerciseRecordEntity();
                    n.setUserId(p.userId());
                    n.setExerciseId(cbt.dbId());
                    n.setCheckDate(day);
                    return n;
                });
        Long planDbId = parsePlanId(req.planId());
        if (planDbId != null) {
            r.setPlanId(planDbId);
            r.setPlanItemSeq(req.planItemSeq());
        }
        r.setCompleted((short) 1);
        r.setDurationActual(req.durationActual());
        recordRepo.save(r);
        if (planDbId != null && req.planItemSeq() != null) {
            try {
                plans.markItemDone(p.userId(), planDbId, req.planItemSeq(), null);
            } catch (Exception e) {
                log.warn("plan item done failed user={}", p.userId(), e);
            }
        }
        safeEvaluate(p.userId());
        return ApiResponse.ok(Map.of("taskNo", taskNo));
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

    // ---------------- internals ----------------

    private void afterCompletion(long userId, Long planDbId, Integer seq, Integer star, LocalDate day) {
        if (planDbId != null && seq != null) {
            try {
                plans.markItemDone(userId, planDbId, seq, null);
            } catch (Exception e) {
                log.warn("plan item done failed user={} plan={}", userId, planDbId, e);
            }
        }
        if (star != null && star >= 1 && star <= 5) writeSelfRating(userId, star, day);
        safeEvaluate(userId);
    }

    /** 行为激活"心情有变化吗"1-5 星 → SELF_RATING 轨迹点（当日覆盖式，与打卡同一语义） */
    private void writeSelfRating(long userId, int star, LocalDate day) {
        try {
            String code = star >= 4 ? "JOY" : (star == 3 ? "CALM" : "TIRED");
            var emo = EmotionCatalog.byCode(code).orElseThrow();
            var existing = trajRepo.findByUserIdAndRecordDateAndSourceType(userId, day, "SELF_RATING");
            EmotionTrajectoryEntity t;
            if (existing.isEmpty()) {
                t = new EmotionTrajectoryEntity();
                t.setUserId(userId);
                t.setRecordDate(day);
                t.setSourceType("SELF_RATING");
            } else {
                t = existing.get(0);
                if (existing.size() > 1) trajRepo.deleteAll(existing.subList(1, existing.size()));
            }
            t.setPrimaryEmotion(emo.label());
            t.setValence(BigDecimal.valueOf((star - 3) / 2.0).setScale(3, RoundingMode.HALF_UP));
            t.setIntensity(BigDecimal.valueOf(0.4).setScale(2, RoundingMode.HALF_UP));
            t.setEventTags(mapper.createArrayNode().add("行为激活复评 " + star + " 星").toString());
            trajRepo.save(t);
        } catch (Exception e) {
            log.warn("self-rating point failed user={} star={}", userId, star, e);
        }
    }

    private void safeEvaluate(long userId) {
        try {
            achievements.evaluate(userId);   // ALL_EXERCISES 等；成就失败不阻断跟练
        } catch (Exception e) {
            log.warn("achievement eval after exercise failed user={}", userId, e);
        }
    }

    private static Long parsePlanId(String planId) {
        if (planId == null || planId.isBlank()) return null;
        try {
            return Long.parseLong(planId.replace("pl_", ""));
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.BAD_PARAMS, "计划编号不认识，请刷新页面重试");
        }
    }
}
