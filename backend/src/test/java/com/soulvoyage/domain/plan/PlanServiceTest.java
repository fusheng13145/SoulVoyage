package com.soulvoyage.domain.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.agent.support.Exercise;
import com.soulvoyage.agent.support.ExerciseCatalog;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.notify.NotificationService;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G4 成长计划的分支覆盖测试：物化（危机降级 / 天数钳制 / 标题截断 / planItems 与 matchedExercises 两条取源）、
 * 跟练回写（首盖不覆盖 / 反馈截断）、自主放弃、到期小结（完成率、完成日 vs 未完成日心情对比、三分支结语）。
 *
 * <p>与 {@code GrowthRhythmTest} 的 Spring 集成测试互补：那条走真实 H2 的 happy path，
 * 本类把各条"数据边界"分支单独钉住，避免边界逻辑只在生产被踩到。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanServiceTest {

    @Mock GrowthPlanRepository planRepo;
    @Mock PlanItemRepository itemRepo;
    @Mock ExerciseCatalog exercises;
    @Mock EmotionTrajectoryRepository trajRepo;
    @Mock ReportRepository reportRepo;
    @Mock UserRepository userRepo;
    @Mock CryptoService crypto;
    @Mock NotificationService notify;

    private final ObjectMapper mapper = new ObjectMapper();
    private final BusinessCalendar cal = new BusinessCalendar("Asia/Shanghai");

    private PlanService service;

    @BeforeEach
    void setUp() {
        service = new PlanService(planRepo, itemRepo, exercises, trajRepo, reportRepo, userRepo,
                crypto, mapper, cal, notify);
    }

    // ---------------- helpers ----------------

    private LocalDate today() {
        return cal.today();
    }

    private UserEntity user(long id, String crisis) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setCrisisState(crisis);
        return u;
    }

    private GrowthPlanEntity plan(Long id, Long userId, String status, LocalDate start, LocalDate end) {
        GrowthPlanEntity p = new GrowthPlanEntity();
        p.setId(id);
        p.setUserId(userId);
        p.setStatus(status);
        p.setStartDate(start);
        p.setEndDate(end);
        p.setDays((short) 3);
        p.setTitle("默认标题");
        p.setSummaryDone((short) 0);
        return p;
    }

    private PlanItemEntity item(Long planId, int seq, LocalDate date, Instant doneAt) {
        PlanItemEntity it = new PlanItemEntity();
        it.setPlanId(planId);
        it.setSeq(seq);
        it.setExerciseCode("ex_478_breath");
        it.setGuidance("跟着做");
        it.setScheduledDate(date);
        it.setDoneAt(doneAt);
        return it;
    }

    private EmotionTrajectoryEntity traj(LocalDate d, String valence) {
        EmotionTrajectoryEntity t = new EmotionTrajectoryEntity();
        t.setUserId(7L);
        t.setRecordDate(d);
        t.setSourceType("SELF_RATING");
        t.setPrimaryEmotion("焦虑");
        t.setValence(valence == null ? null : new BigDecimal(valence));
        t.setIntensity(new BigDecimal("0.500"));
        return t;
    }

    /** 物化时新计划落库要拿到 id，否则返回串拼不出来 */
    private void stubPlanSave(long id) {
        when(planRepo.save(any(GrowthPlanEntity.class))).thenAnswer(inv -> {
            GrowthPlanEntity p = inv.getArgument(0);
            p.setId(id);
            return p;
        });
    }

    private ObjectNode support(String title, int planDays) {
        ObjectNode n = mapper.createObjectNode();
        if (title != null) n.put("planTitle", title);
        n.put("planDays", planDays);
        return n;
    }

    private ArrayNode planItems(ObjectNode support) {
        return support.putArray("planItems");
    }

    private ArrayNode matched(ObjectNode support) {
        return support.putArray("matchedExercises");
    }

    // ---------------- materialize ----------------

    @Test
    @DisplayName("materialize：正常态按 LLM 排期物化，标题/天数/条目与排期日落库")
    void materializeBuildsItemsFromPlanItems() {
        when(userRepo.findById(7L)).thenReturn(Optional.of(user(7L, "NORMAL")));
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of());
        stubPlanSave(1L);
        when(exercises.exists("ex_478_breath")).thenReturn(true);
        when(exercises.exists("ex_cbt_write")).thenReturn(true);

        ObjectNode n = support("把睡眠找回来", 3);
        ArrayNode items = planItems(n);
        items.addObject().put("day", 1).put("exerciseId", "ex_478_breath").put("guidance", "睡前在床上做");
        items.addObject().put("day", 2).put("exerciseId", "ex_cbt_write").put("guidance", "");

        assertEquals("pl_1", service.materialize(7L, 55L, n));

        ArgumentCaptor<GrowthPlanEntity> pc = ArgumentCaptor.forClass(GrowthPlanEntity.class);
        verify(planRepo).save(pc.capture());
        GrowthPlanEntity saved = pc.getValue();
        assertEquals("把睡眠找回来", saved.getTitle());
        assertEquals((short) 3, saved.getDays());
        assertEquals(today(), saved.getStartDate());
        assertEquals(today().plusDays(2), saved.getEndDate());
        assertEquals("ACTION", saved.getStatus());
        assertEquals(55L, saved.getSourceReportId());
        assertEquals(n.toString(), saved.getItemsJson());

        ArgumentCaptor<PlanItemEntity> ic = ArgumentCaptor.forClass(PlanItemEntity.class);
        verify(itemRepo, times(2)).save(ic.capture());
        assertEquals(1L, ic.getAllValues().get(0).getPlanId());
        assertEquals(1, ic.getAllValues().get(0).getSeq());
        assertEquals(today(), ic.getAllValues().get(0).getScheduledDate());
        assertEquals(today().plusDays(1), ic.getAllValues().get(1).getScheduledDate());
        assertEquals("跟着步骤做完就好。", ic.getAllValues().get(1).getGuidance(), "空引导语给出兜底一句");
    }

    @Test
    @DisplayName("materialize：CRISIS 态只落 grounding 单步，忽略模型给的排期")
    void materializeGroundingOnlyInCrisis() {
        when(userRepo.findById(7L)).thenReturn(Optional.of(user(7L, "CRISIS")));
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of());
        stubPlanSave(2L);

        ObjectNode n = support("这个标题不该被采用", 7);
        planItems(n).addObject().put("day", 1).put("exerciseId", "ex_478_breath");

        service.materialize(7L, null, n);

        ArgumentCaptor<GrowthPlanEntity> pc = ArgumentCaptor.forClass(GrowthPlanEntity.class);
        verify(planRepo).save(pc.capture());
        assertEquals("照顾此刻的小计划", pc.getValue().getTitle());
        assertEquals((short) 3, pc.getValue().getDays());
        assertEquals(today().plusDays(2), pc.getValue().getEndDate());

        ArgumentCaptor<PlanItemEntity> ic = ArgumentCaptor.forClass(PlanItemEntity.class);
        verify(itemRepo).save(ic.capture());
        assertEquals("ex_54321", ic.getValue().getExerciseCode());
        assertEquals(today(), ic.getValue().getScheduledDate());
        assertTrue(ic.getValue().getGuidance().contains("5-4-3-2-1"));
        verify(exercises, never()).exists(anyString());
    }

    @Test
    @DisplayName("materialize：COOLING 态与 CRISIS 同样降级为 grounding")
    void materializeGroundingOnlyInCooling() {
        when(userRepo.findById(7L)).thenReturn(Optional.of(user(7L, "COOLING")));
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of());
        stubPlanSave(3L);

        service.materialize(7L, null, support("t", 5));

        ArgumentCaptor<PlanItemEntity> ic = ArgumentCaptor.forClass(PlanItemEntity.class);
        verify(itemRepo).save(ic.capture());
        assertEquals("ex_54321", ic.getValue().getExerciseCode());
    }

    @Test
    @DisplayName("materialize：REVIEW 态不属于降级闭集，走完整排期")
    void materializeReviewStateKeepsFullPlan() {
        when(userRepo.findById(7L)).thenReturn(Optional.of(user(7L, "REVIEW")));
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of());
        stubPlanSave(4L);
        when(exercises.exists("ex_478_breath")).thenReturn(true);

        ObjectNode n = support("复核期也给完整计划", 3);
        planItems(n).addObject().put("day", 1).put("exerciseId", "ex_478_breath").put("guidance", "g");

        service.materialize(7L, null, n);

        ArgumentCaptor<GrowthPlanEntity> pc = ArgumentCaptor.forClass(GrowthPlanEntity.class);
        verify(planRepo).save(pc.capture());
        assertEquals("复核期也给完整计划", pc.getValue().getTitle());
    }

    @Test
    @DisplayName("materialize：用户不存在时按 NORMAL 处理，不因缺行拒绝落计划")
    void materializeTreatsMissingUserAsNormal() {
        when(userRepo.findById(7L)).thenReturn(Optional.empty());
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of());
        stubPlanSave(5L);
        when(exercises.exists("ex_478_breath")).thenReturn(true);

        ObjectNode n = support("计划不断供", 3);
        planItems(n).addObject().put("day", 1).put("exerciseId", "ex_478_breath").put("guidance", "g");

        service.materialize(7L, null, n);

        ArgumentCaptor<GrowthPlanEntity> pc = ArgumentCaptor.forClass(GrowthPlanEntity.class);
        verify(planRepo).save(pc.capture());
        assertEquals("计划不断供", pc.getValue().getTitle());
    }

    @Test
    @DisplayName("materialize：旧 ACTION 计划静默收口为 DROPPED，保证同时只有一个进行中")
    void materializeDropsPreexistingActionPlans() {
        GrowthPlanEntity old = plan(99L, 7L, "ACTION", today().minusDays(5), today().minusDays(3));
        when(userRepo.findById(7L)).thenReturn(Optional.of(user(7L, "NORMAL")));
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of(old));
        stubPlanSave(6L);
        when(exercises.exists("ex_478_breath")).thenReturn(true);

        ObjectNode n = support("新计划", 3);
        planItems(n).addObject().put("day", 1).put("exerciseId", "ex_478_breath").put("guidance", "g");

        service.materialize(7L, null, n);

        assertEquals("DROPPED", old.getStatus());
        verify(planRepo).save(old);
    }

    @Test
    @DisplayName("materialize：planItems 全部非法时回退 matchedExercises，并按每日 ≤2 项摊开")
    void materializeFallsBackToMatchedExercises() {
        when(userRepo.findById(7L)).thenReturn(Optional.of(user(7L, "NORMAL")));
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of());
        stubPlanSave(7L);
        when(exercises.exists("ex_a")).thenReturn(true);
        when(exercises.exists("ex_b")).thenReturn(true);
        when(exercises.exists("ex_c")).thenReturn(true);
        when(exercises.exists("ex_d")).thenReturn(true);

        ObjectNode n = support("摊开", 3);
        planItems(n).addObject().put("day", 1).put("exerciseId", "ex_ghost").put("guidance", "不存在的练习");
        ArrayNode m = matched(n);
        m.addObject().put("exerciseId", "ex_a").put("reason", "理由A");
        m.addObject().put("exerciseId", "ex_b").put("reason", "理由B");
        m.addObject().put("exerciseId", "ex_ghost_2").put("reason", "非法，跳过");
        m.addObject().put("exerciseId", "ex_c").put("reason", "理由C");
        m.addObject().put("exerciseId", "ex_d").put("reason", "理由D");

        service.materialize(7L, null, n);

        ArgumentCaptor<PlanItemEntity> ic = ArgumentCaptor.forClass(PlanItemEntity.class);
        verify(itemRepo, times(4)).save(ic.capture());
        List<PlanItemEntity> saved = ic.getAllValues();
        assertEquals("ex_a", saved.get(0).getExerciseCode());
        assertEquals(today(), saved.get(0).getScheduledDate());
        assertEquals(today(), saved.get(1).getScheduledDate());
        assertEquals(today().plusDays(1), saved.get(2).getScheduledDate());
        assertEquals(today().plusDays(1), saved.get(3).getScheduledDate());
        assertEquals("理由A", saved.get(0).getGuidance());
    }

    @Test
    @DisplayName("materialize：planDays 越大天数越大，7 天封顶；day 超出天数被钳到末日")
    void materializeClampsDaysAndScheduledDate() {
        when(userRepo.findById(7L)).thenReturn(Optional.of(user(7L, "NORMAL")));
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of());
        stubPlanSave(8L);
        when(exercises.exists("ex_a")).thenReturn(true);

        ObjectNode n = support("长期计划", 30);
        planItems(n).addObject().put("day", 30).put("exerciseId", "ex_a").put("guidance", "g");

        service.materialize(7L, null, n);

        ArgumentCaptor<GrowthPlanEntity> pc = ArgumentCaptor.forClass(GrowthPlanEntity.class);
        verify(planRepo).save(pc.capture());
        assertEquals((short) 7, pc.getValue().getDays());
        assertEquals(today().plusDays(6), pc.getValue().getEndDate());

        ArgumentCaptor<PlanItemEntity> ic = ArgumentCaptor.forClass(PlanItemEntity.class);
        verify(itemRepo).save(ic.capture());
        assertEquals(today().plusDays(6), ic.getValue().getScheduledDate());
    }

    @Test
    @DisplayName("materialize：planDays 为 0/缺省时钳到 3 天")
    void materializeClampsDaysToThreeOnMissingValue() {
        when(userRepo.findById(7L)).thenReturn(Optional.of(user(7L, "NORMAL")));
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of());
        stubPlanSave(9L);
        when(exercises.exists("ex_a")).thenReturn(true);

        ObjectNode n = support("默认三天", 0);
        planItems(n).addObject().put("day", 1).put("exerciseId", "ex_a").put("guidance", "g");

        service.materialize(7L, null, n);

        ArgumentCaptor<GrowthPlanEntity> pc = ArgumentCaptor.forClass(GrowthPlanEntity.class);
        verify(planRepo).save(pc.capture());
        assertEquals((short) 3, pc.getValue().getDays());
        assertEquals(today().plusDays(2), pc.getValue().getEndDate());
    }

    @Test
    @DisplayName("materialize：planDays 为 5 时取 5 天中档")
    void materializeClampsDaysToFive() {
        when(userRepo.findById(7L)).thenReturn(Optional.of(user(7L, "NORMAL")));
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of());
        stubPlanSave(10L);
        when(exercises.exists("ex_a")).thenReturn(true);

        ObjectNode n = support("五天计划", 5);
        planItems(n).addObject().put("day", 1).put("exerciseId", "ex_a").put("guidance", "g");

        service.materialize(7L, null, n);

        ArgumentCaptor<GrowthPlanEntity> pc = ArgumentCaptor.forClass(GrowthPlanEntity.class);
        verify(planRepo).save(pc.capture());
        assertEquals((short) 5, pc.getValue().getDays());
        assertEquals(today().plusDays(4), pc.getValue().getEndDate());
    }

    @Test
    @DisplayName("materialize：标题超过 60 字被截断，空标题保持默认文案")
    void materializeTruncatesLongTitleAndKeepsDefault() {
        when(userRepo.findById(7L)).thenReturn(Optional.of(user(7L, "NORMAL")));
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of());
        stubPlanSave(11L);
        when(exercises.exists("ex_a")).thenReturn(true);

        ObjectNode longTitle = support("标".repeat(80), 3);
        planItems(longTitle).addObject().put("day", 1).put("exerciseId", "ex_a").put("guidance", "g");
        service.materialize(7L, null, longTitle);

        ArgumentCaptor<GrowthPlanEntity> pc = ArgumentCaptor.forClass(GrowthPlanEntity.class);
        verify(planRepo).save(pc.capture());
        assertEquals(60, pc.getValue().getTitle().length());

        reset(planRepo);
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of());
        stubPlanSave(12L);

        ObjectNode blankTitle = support(null, 3);
        planItems(blankTitle).addObject().put("day", 1).put("exerciseId", "ex_a").put("guidance", "g");
        service.materialize(7L, null, blankTitle);

        ArgumentCaptor<GrowthPlanEntity> pc2 = ArgumentCaptor.forClass(GrowthPlanEntity.class);
        verify(planRepo).save(pc2.capture());
        assertEquals("照顾此刻的小计划", pc2.getValue().getTitle());
    }

    @Test
    @DisplayName("materialize：模型给了天数但没有可用条目时，同样降级 grounding")
    void materializeFallsBackWhenNoUsableItem() {
        when(userRepo.findById(7L)).thenReturn(Optional.of(user(7L, "NORMAL")));
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of());
        stubPlanSave(13L);

        service.materialize(7L, null, support("没有条目", 3));

        ArgumentCaptor<GrowthPlanEntity> pc = ArgumentCaptor.forClass(GrowthPlanEntity.class);
        verify(planRepo).save(pc.capture());
        assertEquals("照顾此刻的小计划", pc.getValue().getTitle());

        ArgumentCaptor<PlanItemEntity> ic = ArgumentCaptor.forClass(PlanItemEntity.class);
        verify(itemRepo).save(ic.capture());
        assertEquals("ex_54321", ic.getValue().getExerciseCode());
    }

    @Test
    @DisplayName("materialize：条目引导语超过 255 字被截断")
    void materializeTruncatesLongGuidance() {
        when(userRepo.findById(7L)).thenReturn(Optional.of(user(7L, "NORMAL")));
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of());
        stubPlanSave(14L);
        when(exercises.exists("ex_a")).thenReturn(true);

        ObjectNode n = support("长引导", 3);
        planItems(n).addObject().put("day", 1).put("exerciseId", "ex_a").put("guidance", "导".repeat(300));

        service.materialize(7L, null, n);

        ArgumentCaptor<PlanItemEntity> ic = ArgumentCaptor.forClass(PlanItemEntity.class);
        verify(itemRepo).save(ic.capture());
        assertEquals(255, ic.getValue().getGuidance().length());
    }

    // ---------------- active / list / detail ----------------

    @Test
    @DisplayName("active：没有进行中计划时返回 null")
    void activeReturnsNullWithoutRunningPlan() {
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of());
        assertNull(service.active(7L));
    }

    @Test
    @DisplayName("active：计划已过期时返回 null，不把结束的计划当成今日卡")
    void activeReturnsNullWhenWindowPassed() {
        GrowthPlanEntity p = plan(1L, 7L, "ACTION", today().minusDays(10), today().minusDays(8));
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of(p));
        assertNull(service.active(7L));
    }

    @Test
    @DisplayName("active：只展开今日条目，但完成度按全计划统计")
    void activeRendersTodayItemsOnly() {
        GrowthPlanEntity p = plan(1L, 7L, "ACTION", today(), today().plusDays(2));
        when(planRepo.findByUserIdAndStatusOrderByEndDateAsc(7L, "ACTION")).thenReturn(List.of(p));
        when(itemRepo.findByPlanIdOrderBySeqAsc(1L)).thenReturn(List.of(
                item(1L, 1, today(), null),
                item(1L, 2, today().plusDays(1), Instant.now())));
        when(exercises.exists("ex_478_breath")).thenReturn(true);
        when(exercises.require("ex_478_breath"))
                .thenReturn(new Exercise("ex_478_breath", "4-7-8 呼吸", List.of(), List.of(), 4));

        Map<String, Object> view = service.active(7L);

        assertNotNull(view);
        assertEquals("pl_1", view.get("planId"));
        assertEquals(1, ((List<?>) view.get("items")).size());
        assertEquals(2, view.get("totalCount"));
        assertEquals(1, view.get("doneCount"));
        assertEquals(3, ((Number) view.get("daysLeft")).intValue());
        assertEquals("4-7-8 呼吸", ((Map<?, ?>) ((List<?>) view.get("items")).get(0)).get("exerciseName"));
    }

    @Test
    @DisplayName("view：练习不在闭集时用编码兜底，时长记 0；过期计划 daysLeft 归零")
    void viewFallsBackForUnknownExerciseAndExpiredPlan() {
        GrowthPlanEntity p = plan(1L, 7L, "DONE", today().minusDays(5), today().minusDays(3));
        when(planRepo.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(p));
        when(itemRepo.findByPlanIdOrderBySeqAsc(1L)).thenReturn(List.of(item(1L, 1, today().minusDays(5), null)));
        when(exercises.exists("ex_478_breath")).thenReturn(false);

        List<Map<String, Object>> out = service.list(7L);

        assertEquals(1, out.size());
        assertEquals(0, ((Number) out.get(0).get("daysLeft")).intValue());
        Map<?, ?> first = (Map<?, ?>) ((List<?>) out.get(0).get("items")).get(0);
        assertEquals("ex_478_breath", first.get("exerciseName"));
        assertEquals(0, first.get("durationMin"));
        assertNull(first.get("doneAt"));
    }

    @Test
    @DisplayName("detail：非属主一律 NOT_FOUND，不做任何数据返回")
    void detailRejectsNonOwner() {
        when(planRepo.findById(1L)).thenReturn(Optional.of(plan(1L, 9L, "ACTION", today(), today().plusDays(2))));

        BizException ex = assertThrows(BizException.class, () -> service.detail(7L, 1L));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("detail：计划不存在时 NOT_FOUND")
    void detailRejectsMissingPlan() {
        when(planRepo.findById(1L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service.detail(7L, 1L));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    // ---------------- drop ----------------

    @Test
    @DisplayName("drop：进行中的计划被置为 DROPPED，不追问理由")
    void dropMarksRunningPlanDropped() {
        GrowthPlanEntity p = plan(1L, 7L, "ACTION", today(), today().plusDays(2));
        when(planRepo.findById(1L)).thenReturn(Optional.of(p));

        service.drop(7L, 1L);

        assertEquals("DROPPED", p.getStatus());
        verify(planRepo).save(p);
    }

    @Test
    @DisplayName("drop：已结束的计划拒绝再次放弃")
    void dropRejectsFinishedPlan() {
        when(planRepo.findById(1L)).thenReturn(Optional.of(plan(1L, 7L, "DONE", today(), today().plusDays(2))));

        BizException ex = assertThrows(BizException.class, () -> service.drop(7L, 1L));

        assertEquals(ErrorCode.BAD_PARAMS, ex.getErrorCode());
    }

    // ---------------- markItemDone ----------------

    @Test
    @DisplayName("markItemDone：首次盖章记时间，反馈超 255 字截断")
    void markItemDoneStampsAndTruncatesFeedback() {
        when(planRepo.findById(1L)).thenReturn(Optional.of(plan(1L, 7L, "ACTION", today(), today().plusDays(2))));
        PlanItemEntity it = item(1L, 1, today(), null);
        when(itemRepo.findByPlanIdAndSeq(1L, 1)).thenReturn(Optional.of(it));

        service.markItemDone(7L, 1L, 1, "感".repeat(300));

        assertNotNull(it.getDoneAt());
        assertEquals(255, it.getFeedback().length());
        verify(itemRepo).save(it);
    }

    @Test
    @DisplayName("markItemDone：重复回写不覆盖首次完成时间，空白反馈不落库")
    void markItemDoneKeepsFirstStamp() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        when(planRepo.findById(1L)).thenReturn(Optional.of(plan(1L, 7L, "ACTION", today(), today().plusDays(2))));
        PlanItemEntity it = item(1L, 1, today(), first);
        when(itemRepo.findByPlanIdAndSeq(1L, 1)).thenReturn(Optional.of(it));

        service.markItemDone(7L, 1L, 1, "   ");

        assertEquals(first, it.getDoneAt());
        assertNull(it.getFeedback());
    }

    @Test
    @DisplayName("markItemDone：条目序号不存在时 NOT_FOUND")
    void markItemDoneRejectsUnknownSeq() {
        when(planRepo.findById(1L)).thenReturn(Optional.of(plan(1L, 7L, "ACTION", today(), today().plusDays(2))));
        when(itemRepo.findByPlanIdAndSeq(1L, 9)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service.markItemDone(7L, 1L, 9, null));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    // ---------------- 到期小结 ----------------

    private void stubDue(GrowthPlanEntity p) {
        when(planRepo.findByStatusAndEndDateBefore(eq("ACTION"), any(LocalDate.class))).thenReturn(List.of(p));
    }

    @Test
    @DisplayName("小结：已小结过的计划不再重复出报告")
    void summarizeSkipsAlreadySummarized() {
        GrowthPlanEntity p = plan(1L, 7L, "ACTION", today().minusDays(3), today().minusDays(1));
        p.setSummaryDone((short) 1);
        stubDue(p);

        assertEquals(1, service.summarizeDue());

        verify(reportRepo, never()).save(any(ReportEntity.class));
    }

    @Test
    @DisplayName("小结：单条计划抛异常被吞掉，不阻断其余计划的结算")
    void summarizeDueSwallowsFailures() {
        GrowthPlanEntity p = plan(1L, 7L, "ACTION", today().minusDays(3), today().minusDays(1));
        stubDue(p);
        when(itemRepo.findByPlanIdOrderBySeqAsc(1L)).thenThrow(new IllegalStateException("db down"));

        assertEquals(0, service.summarizeDue());
    }

    @Test
    @DisplayName("小结：完成日心情高于未完成日时，给出\"练习真的有用\"的数据结论")
    void summarizeReportsPracticeWorks() {
        GrowthPlanEntity p = plan(1L, 7L, "ACTION", today().minusDays(3), today().minusDays(1));
        p.setTitle("把睡眠找回来");
        stubDue(p);
        when(itemRepo.findByPlanIdOrderBySeqAsc(1L)).thenReturn(List.of(
                item(1L, 1, today().minusDays(3), Instant.now()),
                item(1L, 2, today().minusDays(3), Instant.now()),
                item(1L, 3, today().minusDays(2), null)));
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(7L, today().minusDays(3), today().minusDays(1)))
                .thenReturn(List.of(traj(today().minusDays(3), "0.800"), traj(today().minusDays(2), "0.100")));
        when(reportRepo.save(any(ReportEntity.class))).thenAnswer(inv -> {
            ReportEntity r = inv.getArgument(0);
            r.setId(5L);
            return r;
        });
        when(crypto.encryptUserField(anyLong(), anyString())).thenReturn("enc".getBytes());

        assertEquals(1, service.summarizeDue());

        assertEquals("DROPPED", p.getStatus());
        assertEquals((short) 1, p.getSummaryDone());
        verify(notify).push(eq(7L), eq("PLAN_SUMMARY"), eq("planSummary:1"), eq("plan_summary"),
                any(Map.class), eq("/archives"));

        ArgumentCaptor<ReportEntity> rc = ArgumentCaptor.forClass(ReportEntity.class);
        verify(reportRepo, times(2)).save(rc.capture());
        assertEquals("PLAN_SUMMARY", rc.getValue().getType());
        assertEquals("计划小结 · 把睡眠找回来", rc.getValue().getTitle());
        assertEquals(0L, rc.getValue().getTaskId());
        assertEquals("LOW", rc.getValue().getRiskLevel());
    }

    @Test
    @DisplayName("小结：全部条目完成时计划收口为 DONE")
    void summarizeMarksDoneWhenAllItemsCompleted() {
        GrowthPlanEntity p = plan(1L, 7L, "ACTION", today().minusDays(3), today().minusDays(1));
        stubDue(p);
        when(itemRepo.findByPlanIdOrderBySeqAsc(1L)).thenReturn(List.of(
                item(1L, 1, today().minusDays(3), Instant.now()),
                item(1L, 2, today().minusDays(2), Instant.now())));
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(7L, today().minusDays(3), today().minusDays(1)))
                .thenReturn(List.of());
        when(reportRepo.save(any(ReportEntity.class))).thenAnswer(inv -> {
            ReportEntity r = inv.getArgument(0);
            r.setId(6L);
            return r;
        });
        when(crypto.encryptUserField(anyLong(), anyString())).thenReturn("enc".getBytes());

        service.summarizeDue();

        assertEquals("DONE", p.getStatus());
    }

    @Test
    @DisplayName("小结：一件事都没做时不羞辱，给出\"随时可以从今天那一项重新开始\"")
    void summarizeWithoutAnyCompletion() {
        GrowthPlanEntity p = plan(1L, 7L, "ACTION", today().minusDays(3), today().minusDays(1));
        stubDue(p);
        when(itemRepo.findByPlanIdOrderBySeqAsc(1L)).thenReturn(List.of(
                item(1L, 1, today().minusDays(3), null),
                item(1L, 2, today().minusDays(2), null)));
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(7L, today().minusDays(3), today().minusDays(1)))
                .thenReturn(List.of(traj(today().minusDays(2), null)));
        when(reportRepo.save(any(ReportEntity.class))).thenAnswer(inv -> {
            ReportEntity r = inv.getArgument(0);
            r.setId(7L);
            return r;
        });
        when(crypto.encryptUserField(anyLong(), anyString())).thenReturn("enc".getBytes());

        service.summarizeDue();

        assertEquals("DROPPED", p.getStatus());
    }

    @Test
    @DisplayName("小结：心情均值持平或走低时，不给\"练习有用\"的因果结论")
    void summarizeAvoidsCausalClaimWhenNoLift() {
        GrowthPlanEntity p = plan(1L, 7L, "ACTION", today().minusDays(3), today().minusDays(1));
        stubDue(p);
        when(itemRepo.findByPlanIdOrderBySeqAsc(1L)).thenReturn(List.of(
                item(1L, 1, today().minusDays(3), Instant.now()),
                item(1L, 2, today().minusDays(2), null)));
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(7L, today().minusDays(3), today().minusDays(1)))
                .thenReturn(List.of(traj(today().minusDays(3), "0.100"), traj(today().minusDays(2), "0.900")));
        when(reportRepo.save(any(ReportEntity.class))).thenAnswer(inv -> {
            ReportEntity r = inv.getArgument(0);
            r.setId(8L);
            return r;
        });
        when(crypto.encryptUserField(anyLong(), anyString())).thenReturn("enc".getBytes());

        service.summarizeDue();

        assertEquals("DROPPED", p.getStatus());
    }

    @Test
    @DisplayName("小结：计划没有任何条目时完成率按 0 处理，不发生除零")
    void summarizeHandlesEmptyPlan() {
        GrowthPlanEntity p = plan(1L, 7L, "ACTION", today().minusDays(3), today().minusDays(1));
        stubDue(p);
        when(itemRepo.findByPlanIdOrderBySeqAsc(1L)).thenReturn(List.of());
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(7L, today().minusDays(3), today().minusDays(1)))
                .thenReturn(List.of());
        when(reportRepo.save(any(ReportEntity.class))).thenAnswer(inv -> {
            ReportEntity r = inv.getArgument(0);
            r.setId(9L);
            return r;
        });
        when(crypto.encryptUserField(anyLong(), anyString())).thenReturn("enc".getBytes());

        assertEquals(1, service.summarizeDue());

        assertEquals("DROPPED", p.getStatus());
    }
}
