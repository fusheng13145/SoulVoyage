package com.soulvoyage.agent.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.domain.achievement.AchievementService;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.exercise.ExerciseRecordEntity;
import com.soulvoyage.domain.exercise.ExerciseRecordRepository;
import com.soulvoyage.domain.plan.PlanService;
import com.soulvoyage.domain.task.TaskInstanceEntity;
import com.soulvoyage.orchestrator.OrchestratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 练习库与 C4 沉浸跟练产出的分支覆盖：闭集校验、同日同练习覆盖式打卡、计划条目回写、
 * 行为激活 1-5 星即时复评写 SELF_RATING 轨迹点（当日覆盖、多余点清理）、认知书写转流水线，
 * 以及 limit 钳制与「跟练失败不阻断」的降级路径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExerciseControllerTest {

    @Mock ExerciseCatalog catalog;
    @Mock ExerciseRecordRepository recordRepo;
    @Mock EmotionTrajectoryRepository trajRepo;
    @Mock PlanService plans;
    @Mock AchievementService achievements;
    @Mock OrchestratorService orchestrator;

    private final ObjectMapper mapper = new ObjectMapper();
    private final BusinessCalendar cal = new BusinessCalendar("Asia/Shanghai");
    private final AuthPrincipal user = new AuthPrincipal(1L, "USER");

    private ExerciseController controller;

    @BeforeEach
    void setUp() {
        controller = new ExerciseController(catalog, recordRepo, trajRepo, plans, achievements, cal,
                mapper, orchestrator);
    }

    // ---------------- helpers ----------------

    private Exercise exercise(String id, String name, int durationMin) {
        return new Exercise(id, name, List.of("焦虑"),
                List.of(new Exercise.Step("吸气", "4 秒")), durationMin);
    }

    private void stubSave(long id) {
        when(recordRepo.save(any(ExerciseRecordEntity.class))).thenAnswer(inv -> {
            ExerciseRecordEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(id);
            return e;
        });
    }

    private TaskInstanceEntity task(String taskNo) {
        TaskInstanceEntity t = new TaskInstanceEntity();
        t.setTaskNo(taskNo);
        return t;
    }

    private ExerciseRecordEntity record(String code, Short completed) {
        ExerciseRecordEntity r = new ExerciseRecordEntity();
        r.setId(5L);
        r.setUserId(1L);
        r.setExerciseCode(code);
        r.setCompleted(completed);
        r.setCheckDate(cal.today());
        return r;
    }

    // ---------------- list ----------------

    @Test
    @DisplayName("list：闭集目录按前端契约映射，步骤逐条展开")
    void listMapsCatalogEntries() {
        when(catalog.list()).thenReturn(List.of(exercise("ex_478_breath", "4-7-8 呼吸", 4)));

        List<Map<String, Object>> data = controller.list().getData();

        assertEquals(1, data.size());
        assertEquals("ex_478_breath", data.get(0).get("id"));
        assertEquals("4-7-8 呼吸", data.get(0).get("name"));
        assertEquals(4, data.get(0).get("durationMin"));
        assertEquals(List.of("焦虑"), data.get(0).get("applyEmotions"));
        assertEquals(1, ((List<?>) data.get(0).get("steps")).size());
    }

    // ---------------- checkIn ----------------

    @Test
    @DisplayName("checkIn：新记录按当日日期落库，完成态与反馈一并写入")
    void checkInCreatesNewRecord() {
        when(catalog.require("ex_478_breath")).thenReturn(exercise("ex_478_breath", "4-7-8 呼吸", 4));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(eq(1L), eq("ex_478_breath"), any()))
                .thenReturn(Optional.empty());
        when(trajRepo.findByUserIdAndRecordDateAndSourceType(anyLong(), any(), eq("SELF_RATING")))
                .thenReturn(List.of());
        stubSave(77L);

        Map<String, Object> data = controller.checkIn(user,
                new ExerciseController.CheckInReq("ex_478_breath", null, true, "还不错",
                        null, null, null, 120, null)).getData();

        assertEquals(77L, data.get("id"));
        assertEquals("ex_478_breath", data.get("exerciseId"));
        assertEquals(true, data.get("completed"));

        ArgumentCaptor<ExerciseRecordEntity> c = ArgumentCaptor.forClass(ExerciseRecordEntity.class);
        verify(recordRepo).save(c.capture());
        assertEquals(1L, c.getValue().getUserId());
        assertEquals((short) 1, c.getValue().getCompleted());
        assertEquals(120, c.getValue().getDurationActual());
        assertEquals("还不错", c.getValue().getFeedback());
        assertEquals(cal.today(), c.getValue().getCheckDate());
    }

    @Test
    @DisplayName("checkIn：已存在的同日同练习记录被覆盖更新，不产生第二行")
    void checkInOverwritesSameDayRecord() {
        ExerciseRecordEntity exist = record("ex_478_breath", (short) 0);
        exist.setPlanReportId(9L);
        when(catalog.require("ex_478_breath")).thenReturn(exercise("ex_478_breath", "4-7-8 呼吸", 4));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.of(exist));
        stubSave(0L);

        controller.checkIn(user, new ExerciseController.CheckInReq("ex_478_breath", null, false,
                null, null, null, null, null, null));

        verify(recordRepo).save(exist);
        assertEquals(9L, exist.getPlanReportId(), "planReportId 未传入时保留原值");
        assertEquals((short) 0, exist.getCompleted());
    }

    @Test
    @DisplayName("checkIn：指定排期日时用它，不传时用业务今日")
    void checkInHonoursScheduledDate() {
        LocalDate scheduled = cal.today().minusDays(2);
        when(catalog.require("ex_478_breath")).thenReturn(exercise("ex_478_breath", "4-7-8 呼吸", 4));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        stubSave(78L);

        controller.checkIn(user, new ExerciseController.CheckInReq("ex_478_breath", null, false,
                null, null, null, scheduled, null, null));

        ArgumentCaptor<ExerciseRecordEntity> c = ArgumentCaptor.forClass(ExerciseRecordEntity.class);
        verify(recordRepo).save(c.capture());
        assertEquals(scheduled, c.getValue().getCheckDate());
    }

    @Test
    @DisplayName("checkIn：绑定计划时回写 planId 与条目序号并盖章")
    void checkInBindsPlanAndMarksItemDone() {
        when(catalog.require("ex_478_breath")).thenReturn(exercise("ex_478_breath", "4-7-8 呼吸", 4));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        stubSave(79L);

        controller.checkIn(user, new ExerciseController.CheckInReq("ex_478_breath", null, true,
                null, "pl_12", 3, null, null, null));

        ArgumentCaptor<ExerciseRecordEntity> c = ArgumentCaptor.forClass(ExerciseRecordEntity.class);
        verify(recordRepo).save(c.capture());
        assertEquals(12L, c.getValue().getPlanId());
        assertEquals(3, c.getValue().getPlanItemSeq());
        verify(plans).markItemDone(1L, 12L, 3, null);
    }

    @Test
    @DisplayName("checkIn：计划编号无法识别时拒绝，不落脏数据")
    void checkInRejectsUnparsablePlanId() {
        when(catalog.require("ex_478_breath")).thenReturn(exercise("ex_478_breath", "4-7-8 呼吸", 4));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        stubSave(80L);

        BizException ex = assertThrows(BizException.class, () -> controller.checkIn(user,
                new ExerciseController.CheckInReq("ex_478_breath", null, false, null, "不是编号",
                        null, null, null, null)));

        assertEquals(ErrorCode.BAD_PARAMS, ex.getErrorCode());
    }

    @Test
    @DisplayName("checkIn：未完成时不触发计划回写与成就评估")
    void checkInSkipsAfterCompletionWhenNotCompleted() {
        when(catalog.require("ex_478_breath")).thenReturn(exercise("ex_478_breath", "4-7-8 呼吸", 4));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        stubSave(81L);

        controller.checkIn(user, new ExerciseController.CheckInReq("ex_478_breath", null, false,
                null, "pl_12", 3, null, null, 5));

        verify(plans, never()).markItemDone(anyLong(), anyLong(), anyInt(), any());
        verify(achievements, never()).evaluate(anyLong());
    }

    @Test
    @DisplayName("checkIn：反馈超 255 字截断，负数时长忽略")
    void checkInTruncatesFeedbackAndIgnoresNegativeDuration() {
        when(catalog.require("ex_478_breath")).thenReturn(exercise("ex_478_breath", "4-7-8 呼吸", 4));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        stubSave(82L);

        controller.checkIn(user, new ExerciseController.CheckInReq("ex_478_breath", null, false,
                "感".repeat(300), null, null, null, -5, null));

        ArgumentCaptor<ExerciseRecordEntity> c = ArgumentCaptor.forClass(ExerciseRecordEntity.class);
        verify(recordRepo).save(c.capture());
        assertEquals(255, c.getValue().getFeedback().length());
        assertEquals(null, c.getValue().getDurationActual());
    }

    @Test
    @DisplayName("checkIn：空白反馈不落库")
    void checkInIgnoresBlankFeedback() {
        when(catalog.require("ex_478_breath")).thenReturn(exercise("ex_478_breath", "4-7-8 呼吸", 4));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        stubSave(83L);

        controller.checkIn(user, new ExerciseController.CheckInReq("ex_478_breath", null, false,
                "   ", null, null, null, null, null));

        ArgumentCaptor<ExerciseRecordEntity> c = ArgumentCaptor.forClass(ExerciseRecordEntity.class);
        verify(recordRepo).save(c.capture());
        assertEquals(null, c.getValue().getFeedback());
    }

    @Test
    @DisplayName("checkIn：成就评估失败不阻断跟练落库")
    void checkInSurvivesAchievementFailure() {
        when(catalog.require("ex_478_breath")).thenReturn(exercise("ex_478_breath", "4-7-8 呼吸", 4));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        stubSave(84L);
        doThrow(new IllegalStateException("achievement down")).when(achievements).evaluate(1L);

        Map<String, Object> data = controller.checkIn(user, new ExerciseController.CheckInReq(
                "ex_478_breath", null, true, null, null, null, null, null, null)).getData();

        assertEquals(84L, data.get("id"));
    }

    @Test
    @DisplayName("checkIn：计划条目盖章失败被吞掉，不影响跟练结果")
    void checkInSurvivesPlanMarkFailure() {
        when(catalog.require("ex_478_breath")).thenReturn(exercise("ex_478_breath", "4-7-8 呼吸", 4));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        stubSave(85L);
        doThrow(new BizException(ErrorCode.NOT_FOUND)).when(plans).markItemDone(anyLong(), anyLong(), anyInt(), any());

        Map<String, Object> data = controller.checkIn(user, new ExerciseController.CheckInReq(
                "ex_478_breath", null, true, null, "pl_12", 3, null, null, null)).getData();

        assertEquals(85L, data.get("id"));
    }

    // ---------------- 行为激活 1-5 星 ----------------

    @Test
    @DisplayName("行为激活复评：4-5 星记喜悦、3 星记平静、1-2 星记疲惫，并写入当日轨迹点")
    void writeSelfRatingMapsStarToEmotion() {
        assertEquals("喜悦", ratingFor(5));
        assertEquals("喜悦", ratingFor(4));
        assertEquals("平静", ratingFor(3));
        assertEquals("疲惫", ratingFor(2));
        assertEquals("疲惫", ratingFor(1));
    }

    private String ratingFor(int star) {
        when(catalog.require("ex_478_breath")).thenReturn(exercise("ex_478_breath", "4-7-8 呼吸", 4));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        stubSave(90L);
        when(trajRepo.findByUserIdAndRecordDateAndSourceType(anyLong(), any(), eq("SELF_RATING")))
                .thenReturn(List.of());

        controller.checkIn(user, new ExerciseController.CheckInReq("ex_478_breath", null, true,
                null, null, null, null, null, star));

        ArgumentCaptor<EmotionTrajectoryEntity> c = ArgumentCaptor.forClass(EmotionTrajectoryEntity.class);
        verify(trajRepo, atLeastOnce()).save(c.capture());
        return c.getValue().getPrimaryEmotion();
    }

    @Test
    @DisplayName("行为激活复评：星星越界时不写轨迹点")
    void writeSelfRatingIgnoresOutOfRangeStar() {
        when(catalog.require("ex_478_breath")).thenReturn(exercise("ex_478_breath", "4-7-8 呼吸", 4));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        stubSave(91L);

        controller.checkIn(user, new ExerciseController.CheckInReq("ex_478_breath", null, true,
                null, null, null, null, null, 0));
        controller.checkIn(user, new ExerciseController.CheckInReq("ex_478_breath", null, true,
                null, null, null, null, null, 6));

        verify(trajRepo, never()).save(any());
    }

    @Test
    @DisplayName("行为激活复评：当日已有点时覆盖复用，并清理多余的历史点")
    void writeSelfRatingReusesExistingPoint() {
        when(catalog.require("ex_478_breath")).thenReturn(exercise("ex_478_breath", "4-7-8 呼吸", 4));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        stubSave(92L);
        EmotionTrajectoryEntity first = new EmotionTrajectoryEntity();
        first.setId(1L);
        EmotionTrajectoryEntity dup = new EmotionTrajectoryEntity();
        dup.setId(2L);
        when(trajRepo.findByUserIdAndRecordDateAndSourceType(anyLong(), any(), eq("SELF_RATING")))
                .thenReturn(List.of(first, dup));

        controller.checkIn(user, new ExerciseController.CheckInReq("ex_478_breath", null, true,
                null, null, null, null, null, 4));

        verify(trajRepo).deleteAll(List.of(dup));
        verify(trajRepo).save(first);
        assertEquals("喜悦", first.getPrimaryEmotion());
        assertEquals(new BigDecimal("0.500"), first.getValence());
        assertEquals(new BigDecimal("0.40"), first.getIntensity());
        assertEquals("[\"行为激活复评 4 星\"]", first.getEventTags());
    }

    @Test
    @DisplayName("行为激活复评：轨迹写入失败被吞掉，不阻断跟练")
    void writeSelfRatingSwallowsFailure() {
        when(catalog.require("ex_478_breath")).thenReturn(exercise("ex_478_breath", "4-7-8 呼吸", 4));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        stubSave(93L);
        when(trajRepo.findByUserIdAndRecordDateAndSourceType(anyLong(), any(), eq("SELF_RATING")))
                .thenThrow(new IllegalStateException("traj down"));

        Map<String, Object> data = controller.checkIn(user, new ExerciseController.CheckInReq(
                "ex_478_breath", null, true, null, null, null, null, null, 4)).getData();

        assertEquals(93L, data.get("id"));
    }

    // ---------------- cognitive ----------------

    @Test
    @DisplayName("认知书写：三栏拼成正文提交流水线，并自动完成 ex_cbt_write")
    void cognitiveSubmitsPipelineAndCompletesExercise() {
        when(orchestrator.submit(anyLong(), eq("COGNITIVE_PIPELINE"), any(), any()))
                .thenReturn(task("TK-COG"));
        when(catalog.require("ex_cbt_write")).thenReturn(exercise("ex_cbt_write", "认知书写", 10));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(eq(1L), eq("ex_cbt_write"), any()))
                .thenReturn(Optional.empty());
        stubSave(88L);

        Map<String, String> data = controller.cognitive(user,
                new ExerciseController.CognitiveReq("室友没回消息", "他肯定讨厌我", "他可能在忙",
                        "pl_9", 3, 60)).getData();

        assertEquals("TK-COG", data.get("taskNo"));
        ArgumentCaptor<com.fasterxml.jackson.databind.node.ObjectNode> ic =
                ArgumentCaptor.forClass(com.fasterxml.jackson.databind.node.ObjectNode.class);
        verify(orchestrator).submit(eq(1L), eq("COGNITIVE_PIPELINE"), ic.capture(), eq(null));
        assertEquals("EXERCISE", ic.getValue().path("sourceType").asText());
        assertEquals(cal.today().toString(), ic.getValue().path("recordDate").asText());
        assertEquals("情境：室友没回消息\n自动想法：他肯定讨厌我\n替代想法：他可能在忙",
                ic.getValue().path("diaryText").asText());

        verify(plans).markItemDone(1L, 9L, 3, null);
        verify(achievements).evaluate(1L);
    }

    @Test
    @DisplayName("认知书写：未绑定计划条目时只完成练习，不回写计划")
    void cognitiveWithoutPlanBinding() {
        when(orchestrator.submit(anyLong(), anyString(), any(), any())).thenReturn(task("TK-COG2"));
        when(catalog.require("ex_cbt_write")).thenReturn(exercise("ex_cbt_write", "认知书写", 10));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        stubSave(89L);

        controller.cognitive(user, new ExerciseController.CognitiveReq("a", "b", "c", null, null, 30));

        verify(plans, never()).markItemDone(anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("认知书写：计划回写失败被吞掉，练习仍算完成")
    void cognitiveSurvivesPlanMarkFailure() {
        when(orchestrator.submit(anyLong(), anyString(), any(), any())).thenReturn(task("TK-COG3"));
        when(catalog.require("ex_cbt_write")).thenReturn(exercise("ex_cbt_write", "认知书写", 10));
        when(recordRepo.findByUserIdAndExerciseCodeAndCheckDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.of(record("ex_cbt_write", (short) 0)));
        stubSave(0L);
        doThrow(new BizException(ErrorCode.NOT_FOUND)).when(plans).markItemDone(anyLong(), anyLong(), anyInt(), any());

        Map<String, String> data = controller.cognitive(user,
                new ExerciseController.CognitiveReq("a", "b", "c", "pl_9", 1, 30)).getData();

        assertEquals("TK-COG3", data.get("taskNo"));
    }

    // ---------------- records ----------------

    @Test
    @DisplayName("records：limit 越界被钳到 1..100，练习名从闭集解析")
    void recordsClampsLimitAndResolvesName() {
        when(catalog.list()).thenReturn(List.of(exercise("ex_478_breath", "4-7-8 呼吸", 4)));
        when(recordRepo.findByUserIdOrderByCreatedAtDescIdDesc(eq(1L), any()))
                .thenReturn(List.of(record("ex_478_breath", (short) 1)));

        List<Map<String, Object>> data = controller.records(user, 0).getData();

        assertEquals(1, data.size());
        assertEquals("4-7-8 呼吸", data.get(0).get("exerciseName"));
        assertEquals(true, data.get(0).get("completed"));
        assertEquals("", data.get(0).get("feedback"));
        assertEquals("", data.get(0).get("createdAt"));

        controller.records(user, 500);

        ArgumentCaptor<Pageable> pc = ArgumentCaptor.forClass(Pageable.class);
        verify(recordRepo, times(2)).findByUserIdOrderByCreatedAtDescIdDesc(eq(1L), pc.capture());
        assertEquals(1, pc.getAllValues().get(0).getPageSize());
        assertEquals(100, pc.getAllValues().get(1).getPageSize());
    }

    @Test
    @DisplayName("records：练习已下架时名称回落到泛称")
    void recordsFallsBackWhenExerciseMissing() {
        when(catalog.list()).thenReturn(List.of());
        when(recordRepo.findByUserIdOrderByCreatedAtDescIdDesc(eq(1L), any()))
                .thenReturn(List.of(record("ex_removed", (short) 0)));

        List<Map<String, Object>> data = controller.records(user, 20).getData();

        assertEquals("练习", data.get(0).get("exerciseName"));
        assertEquals(false, data.get(0).get("completed"));
    }
}
