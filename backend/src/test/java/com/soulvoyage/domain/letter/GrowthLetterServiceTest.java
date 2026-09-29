package com.soulvoyage.domain.letter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.checkin.MoodCheckInEntity;
import com.soulvoyage.domain.checkin.MoodCheckInRepository;
import com.soulvoyage.domain.diary.DiaryEntity;
import com.soulvoyage.domain.diary.DiaryRepository;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.exercise.ExerciseRecordEntity;
import com.soulvoyage.domain.exercise.ExerciseRecordRepository;
import com.soulvoyage.domain.notify.UserPreferencesEntity;
import com.soulvoyage.domain.notify.UserPreferencesRepository;
import com.soulvoyage.domain.profile.EmotionProfileEntity;
import com.soulvoyage.domain.profile.EmotionProfileRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G6 成长来信的分支覆盖测试：周扫（受众过滤、同周幂等、素材不足宁缺毋滥、单用户失败不阻断）、
 * 闭集素材组装（情绪计数、环比周、压力源截断、练习完成过滤、引文只取自己的原话）、
 * 以及读取侧的解密降级。与 {@code GrowthRhythmTest} 的集成路径互补，专打数据边界。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GrowthLetterServiceTest {

    @Mock UserPreferencesRepository prefsRepo;
    @Mock EmotionTrajectoryRepository trajRepo;
    @Mock DiaryRepository diaryRepo;
    @Mock MoodCheckInRepository checkInRepo;
    @Mock ExerciseRecordRepository exerciseRepo;
    @Mock EmotionProfileRepository profileRepo;
    @Mock GrowthLetterRepository letterRepo;
    @Mock CryptoService crypto;
    @Mock OrchestratorService orchestrator;

    private final ObjectMapper mapper = new ObjectMapper();
    private final BusinessCalendar cal = new BusinessCalendar("Asia/Shanghai");

    private GrowthLetterService service;

    /** 取一个周一作扫描日，保证「上周起点」与「周内第一天」重合，便于断言 clientReqId */
    private final LocalDate runDate = LocalDate.of(2026, 9, 21);

    @BeforeEach
    void setUp() {
        service = new GrowthLetterService(prefsRepo, trajRepo, diaryRepo, checkInRepo, exerciseRepo,
                profileRepo, letterRepo, crypto, mapper, cal, orchestrator);
    }

    // ---------------- helpers ----------------

    private LocalDate weekStart() {
        return runDate.minusWeeks(1).with(WeekFields.ISO.dayOfWeek(), 1);
    }

    private LocalDate weekEnd() {
        return weekStart().plusDays(6);
    }

    private String statWeek() {
        WeekFields wf = WeekFields.ISO;
        LocalDate target = runDate.minusWeeks(1);
        return target.get(wf.weekBasedYear()) + "-W"
                + String.format("%02d", target.get(wf.weekOfWeekBasedYear()));
    }

    private UserPreferencesEntity prefs(long userId) {
        UserPreferencesEntity p = new UserPreferencesEntity();
        p.setUserId(userId);
        p.setLetterOn((short) 1);
        return p;
    }

    private EmotionTrajectoryEntity traj(long userId, LocalDate d, String emotion, String valence) {
        EmotionTrajectoryEntity t = new EmotionTrajectoryEntity();
        t.setUserId(userId);
        t.setRecordDate(d);
        t.setSourceType("SELF_RATING");
        t.setPrimaryEmotion(emotion);
        t.setValence(valence == null ? null : new BigDecimal(valence));
        t.setIntensity(new BigDecimal("0.500"));
        return t;
    }

    private DiaryEntity diary(long userId, LocalDate d, byte[] content) {
        DiaryEntity e = new DiaryEntity();
        e.setUserId(userId);
        e.setRecordDate(d);
        e.setContentEnc(content);
        return e;
    }

    private MoodCheckInEntity checkIn(long userId, LocalDate d, byte[] note) {
        MoodCheckInEntity c = new MoodCheckInEntity();
        c.setUserId(userId);
        c.setCheckDate(d);
        c.setEmotionCode("CALM");
        c.setNoteEnc(note);
        return c;
    }

    private ExerciseRecordEntity exercise(long userId, LocalDate d, Short completed) {
        ExerciseRecordEntity e = new ExerciseRecordEntity();
        e.setUserId(userId);
        e.setExerciseCode("ex_478_breath");
        e.setCheckDate(d);
        e.setCompleted(completed);
        e.setCreatedAt(d.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        return e;
    }

    private EmotionProfileEntity profileJson(String stressorTopJson) {
        EmotionProfileEntity p = new EmotionProfileEntity();
        p.setUserId(7L);
        p.setStatWeek(statWeek());
        p.setStressorTopJson(stressorTopJson);
        return p;
    }

    /** 三个同情绪数据点，恰好越过来信门槛 */
    private List<EmotionTrajectoryEntity> threePoints(long userId) {
        return List.of(
                traj(userId, weekStart(), "焦虑", "0.100"),
                traj(userId, weekStart().plusDays(1), "焦虑", "0.100"),
                traj(userId, weekStart().plusDays(2), "焦虑", "0.100"));
    }

    /** 把「本周有素材、上周为空、无练习无打卡无日记」的通用桩铺好 */
    private void stubMinimalMaterial(long userId) {
        when(prefsRepo.findByLetterOnOrderByUserId((short) 1)).thenReturn(List.of(prefs(userId)));
        when(letterRepo.findByUserIdAndStatWeek(eq(userId), eq(statWeek()))).thenReturn(Optional.empty());
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(eq(userId), eq(weekStart()), eq(weekEnd())))
                .thenReturn(threePoints(userId));
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
                eq(userId), eq(weekStart().minusWeeks(1)), eq(weekEnd().minusWeeks(1)))).thenReturn(List.of());
        when(exerciseRepo.findByUserIdOrderByCreatedAtDescIdDesc(userId)).thenReturn(List.of());
        when(checkInRepo.findByUserIdAndCheckDateBetweenOrderByCheckDateAsc(userId, weekStart(), weekEnd()))
                .thenReturn(List.of());
        when(diaryRepo.findByUserIdAndDeletedAtIsNullOrderByRecordDateDescIdDesc(userId)).thenReturn(List.of());
    }

    private JsonNode capturedMaterial() {
        ArgumentCaptor<JsonNode> ic = ArgumentCaptor.forClass(JsonNode.class);
        verify(orchestrator).submit(eq(7L), eq("GROWTH_LETTER_PIPELINE"), ic.capture(), anyString());
        return ic.getValue().path("material");
    }

    // ---------------- sweep ----------------

    @Test
    @DisplayName("sweep：素材不足三个数据点时不写信，宁缺毋滥")
    void sweepSkipsWhenMaterialIsThin() {
        when(prefsRepo.findByLetterOnOrderByUserId((short) 1)).thenReturn(List.of(prefs(7L)));
        when(letterRepo.findByUserIdAndStatWeek(7L, statWeek())).thenReturn(Optional.empty());
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(7L, weekStart(), weekEnd()))
                .thenReturn(List.of(traj(7L, weekStart(), "焦虑", "-0.400")));

        assertEquals(0, service.sweep(runDate));
        verify(orchestrator, never()).submit(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("sweep：同一 ISO 周已出过信时直接跳过，重跑不重复花配额")
    void sweepSkipsAlreadyGeneratedWeek() {
        GrowthLetterEntity exist = new GrowthLetterEntity();
        exist.setUserId(7L);
        exist.setStatWeek(statWeek());
        when(prefsRepo.findByLetterOnOrderByUserId((short) 1)).thenReturn(List.of(prefs(7L)));
        when(letterRepo.findByUserIdAndStatWeek(7L, statWeek())).thenReturn(Optional.of(exist));

        assertEquals(0, service.sweep(runDate));
        verify(orchestrator, never()).submit(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("sweep：没有开启来信的用户时直接返回 0")
    void sweepReturnsZeroWithoutAudience() {
        when(prefsRepo.findByLetterOnOrderByUserId((short) 1)).thenReturn(List.of());

        assertEquals(0, service.sweep(runDate));
    }

    @Test
    @DisplayName("sweep：clientReqId 按「letter:周:用户」拼装，保证幂等可重放")
    void sweepUsesIdempotentClientReqId() {
        stubMinimalMaterial(7L);

        assertEquals(1, service.sweep(runDate));

        verify(orchestrator).submit(eq(7L), eq("GROWTH_LETTER_PIPELINE"), any(),
                eq("letter:" + statWeek() + ":7"));
    }

    @Test
    @DisplayName("sweep：单用户提交失败被吞掉，不影响其余用户结算")
    void sweepSwallowsPerUserFailure() {
        when(prefsRepo.findByLetterOnOrderByUserId((short) 1)).thenReturn(List.of(prefs(7L), prefs(8L)));
        when(letterRepo.findByUserIdAndStatWeek(7L, statWeek())).thenReturn(Optional.empty());
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(eq(7L), any(), any()))
                .thenThrow(new IllegalStateException("db down"));
        when(letterRepo.findByUserIdAndStatWeek(8L, statWeek())).thenReturn(Optional.empty());
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(eq(8L), eq(weekStart()), eq(weekEnd())))
                .thenReturn(threePoints(8L));
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
                eq(8L), eq(weekStart().minusWeeks(1)), eq(weekEnd().minusWeeks(1)))).thenReturn(List.of());
        when(exerciseRepo.findByUserIdOrderByCreatedAtDescIdDesc(8L)).thenReturn(List.of());
        when(checkInRepo.findByUserIdAndCheckDateBetweenOrderByCheckDateAsc(8L, weekStart(), weekEnd()))
                .thenReturn(List.of());
        when(diaryRepo.findByUserIdAndDeletedAtIsNullOrderByRecordDateDescIdDesc(8L)).thenReturn(List.of());

        assertEquals(1, service.sweep(runDate));
        verify(orchestrator).submit(eq(8L), eq("GROWTH_LETTER_PIPELINE"), any(), anyString());
    }

    @Test
    @DisplayName("sweep：多个用户各自独立提交，条数即成功提交数")
    void sweepSubmitsOneLetterPerUser() {
        when(prefsRepo.findByLetterOnOrderByUserId((short) 1)).thenReturn(List.of(prefs(7L), prefs(8L)));
        when(letterRepo.findByUserIdAndStatWeek(anyLong(), eq(statWeek()))).thenReturn(Optional.empty());
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), eq(weekStart()), eq(weekEnd())))
                .thenReturn(threePoints(7L));
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
                anyLong(), eq(weekStart().minusWeeks(1)), eq(weekEnd().minusWeeks(1)))).thenReturn(List.of());
        when(exerciseRepo.findByUserIdOrderByCreatedAtDescIdDesc(anyLong())).thenReturn(List.of());
        when(checkInRepo.findByUserIdAndCheckDateBetweenOrderByCheckDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of());
        when(diaryRepo.findByUserIdAndDeletedAtIsNullOrderByRecordDateDescIdDesc(anyLong())).thenReturn(List.of());

        assertEquals(2, service.sweep(runDate));
        verify(orchestrator, times(2)).submit(anyLong(), eq("GROWTH_LETTER_PIPELINE"), any(), anyString());
    }

    @Test
    @DisplayName("mondaySweep：定时入口吞掉异常，不让调度线程死掉")
    void mondaySweepSwallowsFailure() {
        when(prefsRepo.findByLetterOnOrderByUserId((short) 1)).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> service.mondaySweep());
    }

    // ---------------- buildInput ----------------

    @Test
    @DisplayName("buildInput：情绪出现最多者入选 emotionTop，均值四舍五入两位")
    void buildInputPicksTopEmotionAndAveragesValence() {
        when(prefsRepo.findByLetterOnOrderByUserId((short) 1)).thenReturn(List.of(prefs(7L)));
        when(letterRepo.findByUserIdAndStatWeek(7L, statWeek())).thenReturn(Optional.empty());
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(7L, weekStart(), weekEnd()))
                .thenReturn(List.of(
                        traj(7L, weekStart(), "焦虑", "-0.400"),
                        traj(7L, weekStart().plusDays(1), "焦虑", "-0.200"),
                        traj(7L, weekStart().plusDays(2), "喜悦", "0.600")));
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
                7L, weekStart().minusWeeks(1), weekEnd().minusWeeks(1))).thenReturn(List.of());
        when(exerciseRepo.findByUserIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(List.of());
        when(checkInRepo.findByUserIdAndCheckDateBetweenOrderByCheckDateAsc(7L, weekStart(), weekEnd()))
                .thenReturn(List.of());
        when(diaryRepo.findByUserIdAndDeletedAtIsNullOrderByRecordDateDescIdDesc(7L)).thenReturn(List.of());

        assertEquals(1, service.sweep(runDate));

        JsonNode m = capturedMaterial();
        assertEquals(3, m.path("dataPoints").asInt());
        assertEquals(0.0, m.path("avgValence").asDouble(), 0.001);        // (-0.4-0.2+0.6)/3
        assertEquals("焦虑", m.path("emotionTop").asText());              // 2 次 > 1 次
        assertFalse(m.has("prevAvgValence"), "上一周无数据时不做环比");
    }

    @Test
    @DisplayName("buildInput：上一周有数据时写出环比均值；本周效价缺失按 0 计入")
    void buildInputComputesPrevWeekAverage() {
        when(prefsRepo.findByLetterOnOrderByUserId((short) 1)).thenReturn(List.of(prefs(7L)));
        when(letterRepo.findByUserIdAndStatWeek(7L, statWeek())).thenReturn(Optional.empty());
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(7L, weekStart(), weekEnd()))
                .thenReturn(List.of(
                        traj(7L, weekStart(), "焦虑", "-0.400"),
                        traj(7L, weekStart().plusDays(1), "焦虑", null),
                        traj(7L, weekStart().plusDays(2), "焦虑", "0.400")));
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
                7L, weekStart().minusWeeks(1), weekEnd().minusWeeks(1)))
                .thenReturn(List.of(
                        traj(7L, weekStart().minusWeeks(1), "平静", "-1.000"),
                        traj(7L, weekStart().minusWeeks(1).plusDays(1), "平静", "0.000")));
        when(exerciseRepo.findByUserIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(List.of());
        when(checkInRepo.findByUserIdAndCheckDateBetweenOrderByCheckDateAsc(7L, weekStart(), weekEnd()))
                .thenReturn(List.of());
        when(diaryRepo.findByUserIdAndDeletedAtIsNullOrderByRecordDateDescIdDesc(7L)).thenReturn(List.of());

        assertEquals(1, service.sweep(runDate));

        JsonNode m = capturedMaterial();
        assertEquals(0.0, m.path("avgValence").asDouble(), 0.001);
        assertEquals(-0.5, m.path("prevAvgValence").asDouble(), 0.001);
    }

    @Test
    @DisplayName("buildInput：压力源 JSON 合法时截断到 3 条且跳过空名，非法时静默跳过")
    void buildInputTruncatesStressorTop() {
        stubMinimalMaterial(7L);
        when(profileRepo.findByUserIdAndStatWeek(7L, statWeek())).thenReturn(Optional.of(profileJson("""
                [{"name":"学业压力"},{"name":"人际冲突"},{"name":"睡眠不足"},{"name":"不该出现"},
                 {"name":""},{"plain":"裸字符串"}]""")));

        assertEquals(1, service.sweep(runDate));

        JsonNode stressors = capturedMaterial().path("stressorTop");
        assertEquals(3, stressors.size());
        assertEquals("学业压力", stressors.get(0).asText());
        assertEquals("睡眠不足", stressors.get(2).asText());
    }

    @Test
    @DisplayName("buildInput：压力源 JSON 损坏时退回空列表，不让调度崩掉")
    void buildInputSurvivesBrokenStressorJson() {
        stubMinimalMaterial(7L);
        when(profileRepo.findByUserIdAndStatWeek(7L, statWeek()))
                .thenReturn(Optional.of(profileJson("{ 这不是数组")));

        assertEquals(1, service.sweep(runDate));

        assertEquals(0, capturedMaterial().path("stressorTop").size());
    }

    @Test
    @DisplayName("buildInput：压力源为 null 时按空数组处理")
    void buildInputHandlesNullStressorJson() {
        stubMinimalMaterial(7L);
        when(profileRepo.findByUserIdAndStatWeek(7L, statWeek()))
                .thenReturn(Optional.of(profileJson(null)));

        assertEquals(1, service.sweep(runDate));

        assertEquals(0, capturedMaterial().path("stressorTop").size());
    }

    @Test
    @DisplayName("buildInput：练习完成数只统计周内且 completed=1 的记录")
    void buildInputCountsOnlyCompletedInWeekExercises() {
        stubMinimalMaterial(7L);
        ExerciseRecordEntity noCreatedAt = exercise(7L, weekStart(), (short) 1);
        noCreatedAt.setCreatedAt(null);
        when(exerciseRepo.findByUserIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(List.of(
                exercise(7L, weekStart(), (short) 1),
                exercise(7L, weekStart().plusDays(1), (short) 0),
                exercise(7L, weekStart().plusDays(2), null),
                exercise(7L, weekStart().minusDays(3), (short) 1),
                noCreatedAt));

        assertEquals(1, service.sweep(runDate));

        assertEquals(1, capturedMaterial().path("exercisesDone").asInt());
    }

    @Test
    @DisplayName("buildInput：streakDays 记本周打卡天数")
    void buildInputCountsCheckInDays() {
        stubMinimalMaterial(7L);

        assertEquals(1, service.sweep(runDate));

        assertEquals(0, capturedMaterial().path("streakDays").asInt());
    }

    @Test
    @DisplayName("buildInput：引文优先取日记原句，且最多两条")
    void buildInputCollectsQuotesFromDiary() {
        stubMinimalMaterial(7L);
        when(diaryRepo.findByUserIdAndDeletedAtIsNullOrderByRecordDateDescIdDesc(7L)).thenReturn(List.of(
                diary(7L, weekStart(), "enc1".getBytes()),
                diary(7L, weekStart().plusDays(1), "enc2".getBytes()),
                diary(7L, weekStart().plusDays(2), "enc3".getBytes()),
                diary(7L, weekStart().minusDays(4), "enc-old".getBytes())));
        when(crypto.decryptUserField(7L, "enc1".getBytes())).thenReturn("今天散步看到了晚霞，心里松了一点。");
        when(crypto.decryptUserField(7L, "enc2".getBytes())).thenReturn("短。");
        when(crypto.decryptUserField(7L, "enc3".getBytes())).thenReturn("本来以为会很难，结果还算顺利。");

        assertEquals(1, service.sweep(runDate));

        JsonNode m = capturedMaterial();
        JsonNode quotes = m.path("quotes");
        assertEquals(2, quotes.size(), "日记引文上限两条");
        assertEquals("今天散步看到了晚霞，心里松了一点", quotes.get(0).asText());
        assertFalse(m.path("insufficient").asBoolean(), "有原话时不标证据不足");
    }

    @Test
    @DisplayName("buildInput：日记解密失败被吞掉，不冒充引用，并如实标证据不足")
    void buildInputSkipsUndecryptableDiary() {
        stubMinimalMaterial(7L);
        when(diaryRepo.findByUserIdAndDeletedAtIsNullOrderByRecordDateDescIdDesc(7L))
                .thenReturn(List.of(diary(7L, weekStart(), "bad".getBytes())));
        when(crypto.decryptUserField(7L, "bad".getBytes())).thenThrow(new IllegalStateException("key destroyed"));

        assertEquals(1, service.sweep(runDate));

        JsonNode m = capturedMaterial();
        assertEquals(0, m.path("quotes").size());
        assertTrue(m.path("insufficient").asBoolean(), "无可用原话时如实标证据不足");
    }

    @Test
    @DisplayName("buildInput：日记不足时用打卡一句话补足，空白与超 40 字不入选")
    void buildInputBackfillsQuotesFromCheckInNote() {
        stubMinimalMaterial(7L);
        when(checkInRepo.findByUserIdAndCheckDateBetweenOrderByCheckDateAsc(7L, weekStart(), weekEnd()))
                .thenReturn(List.of(
                        checkIn(7L, weekStart(), null),
                        checkIn(7L, weekStart().plusDays(1), "n1".getBytes()),
                        checkIn(7L, weekStart().plusDays(2), "n2".getBytes()),
                        checkIn(7L, weekStart().plusDays(3), "n3".getBytes())));
        when(crypto.decryptUserField(7L, "n1".getBytes())).thenReturn("今天和室友把话说开了");
        when(crypto.decryptUserField(7L, "n2".getBytes())).thenReturn("嗯。");
        when(crypto.decryptUserField(7L, "n3".getBytes())).thenReturn("长".repeat(41));

        assertEquals(1, service.sweep(runDate));

        JsonNode quotes = capturedMaterial().path("quotes");
        assertEquals(2, quotes.size(), "超过 40 字的打卡一句话不入选");
        assertEquals("今天和室友把话说开了", quotes.get(0).asText());
        assertEquals("嗯。", quotes.get(1).asText());
    }

    @Test
    @DisplayName("buildInput：打卡一句话解密失败时静默跳过")
    void buildInputSkipsUndecryptableCheckInNote() {
        stubMinimalMaterial(7L);
        when(checkInRepo.findByUserIdAndCheckDateBetweenOrderByCheckDateAsc(7L, weekStart(), weekEnd()))
                .thenReturn(List.of(checkIn(7L, weekStart(), "bad".getBytes())));
        when(crypto.decryptUserField(7L, "bad".getBytes())).thenThrow(new IllegalStateException("key destroyed"));

        assertEquals(1, service.sweep(runDate));

        JsonNode m = capturedMaterial();
        assertEquals(0, m.path("quotes").size());
        assertTrue(m.path("insufficient").asBoolean());
    }

    // ---------------- pickSentence ----------------

    @Test
    @DisplayName("pickSentence：按断句切分后取长度 6-40 的最长一句")
    void pickSentenceTakesLongestWithinRange() {
        assertEquals("今天散步看到了晚霞心里松了一点",
                GrowthLetterService.pickSentence("短句。今天散步看到了晚霞心里松了一点！还有别的。"));
    }

    @Test
    @DisplayName("pickSentence：全部句子过短或过长时返回 null")
    void pickSentenceReturnsNullWhenNothingFits() {
        assertNull(GrowthLetterService.pickSentence("好。很好。好得很。"));
        assertNull(GrowthLetterService.pickSentence("长".repeat(41) + "。"));
    }

    @Test
    @DisplayName("pickSentence：恰好 6 字与 40 字都算合格")
    void pickSentenceAcceptsBoundaryLengths() {
        assertEquals("一二三四五六", GrowthLetterService.pickSentence("一二三四五六。"));
        String exactly40 = "字".repeat(40);
        assertEquals(exactly40, GrowthLetterService.pickSentence(exactly40 + "。"));
    }

    // ---------------- list ----------------

    @Test
    @DisplayName("list：正常解密回传正文与亮点句")
    void listDecryptsLetter() {
        GrowthLetterEntity l = new GrowthLetterEntity();
        l.setId(11L);
        l.setUserId(7L);
        l.setStatWeek(statWeek());
        l.setCreatedAt(Instant.parse("2026-09-21T00:00:00Z"));
        l.setContentEnc("c".getBytes());
        when(letterRepo.findByUserIdOrderByStatWeekDesc(7L)).thenReturn(List.of(l));
        when(crypto.decryptUserField(7L, "c".getBytes()))
                .thenReturn("{\"letter\":\"见字如面。\",\"weekGlow\":\"最亮的一刻\"}");

        List<Map<String, Object>> out = service.list(7L);

        assertEquals(1, out.size());
        assertEquals("gl_11", out.get(0).get("id"));
        assertEquals(statWeek(), out.get(0).get("statWeek"));
        assertEquals("见字如面。", out.get(0).get("letter"));
        assertEquals("最亮的一刻", out.get(0).get("weekGlow"));
    }

    @Test
    @DisplayName("list：密钥已销毁时降级为占位文案，不抛异常中断整页")
    void listDegradesWhenKeyDestroyed() {
        GrowthLetterEntity l = new GrowthLetterEntity();
        l.setId(12L);
        l.setUserId(7L);
        l.setStatWeek(statWeek());
        l.setCreatedAt(Instant.parse("2026-09-21T00:00:00Z"));
        l.setContentEnc("c".getBytes());
        when(letterRepo.findByUserIdOrderByStatWeekDesc(7L)).thenReturn(List.of(l));
        when(crypto.decryptUserField(7L, "c".getBytes())).thenThrow(new IllegalStateException("key destroyed"));

        List<Map<String, Object>> out = service.list(7L);

        assertEquals("（无法解密：密钥已销毁）", out.get(0).get("letter"));
        assertEquals("", out.get(0).get("weekGlow"));
    }

    @Test
    @DisplayName("list：没有任何来信时返回空集合")
    void listReturnsEmptyWithoutLetters() {
        when(letterRepo.findByUserIdOrderByStatWeekDesc(7L)).thenReturn(List.of());

        assertTrue(service.list(7L).isEmpty());
    }
}
