package com.soulvoyage.domain.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.audit.AuditService;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.checkin.MoodCheckInEntity;
import com.soulvoyage.domain.checkin.MoodCheckInRepository;
import com.soulvoyage.domain.companion.CompanionSessionEntity;
import com.soulvoyage.domain.companion.CompanionSessionRepository;
import com.soulvoyage.domain.companion.CompanionTurnEntity;
import com.soulvoyage.domain.companion.CompanionTurnRepository;
import com.soulvoyage.domain.content.ReadingService;
import com.soulvoyage.domain.diary.DiaryEntity;
import com.soulvoyage.domain.diary.DiaryRepository;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.exercise.ExerciseRecordEntity;
import com.soulvoyage.domain.exercise.ExerciseRecordRepository;
import com.soulvoyage.domain.export.ExportRecordEntity;
import com.soulvoyage.domain.export.ExportRecordRepository;
import com.soulvoyage.domain.export.ExportStore;
import com.soulvoyage.domain.export.InMemoryExportStore;
import com.soulvoyage.domain.letter.GrowthLetterEntity;
import com.soulvoyage.domain.letter.GrowthLetterRepository;
import com.soulvoyage.domain.profile.EmotionProfileEntity;
import com.soulvoyage.domain.profile.EmotionProfileRepository;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
import com.soulvoyage.domain.risk.RiskEventEntity;
import com.soulvoyage.domain.risk.RiskEventRepository;
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
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成长档案与限时导出的分支覆盖：周报（画像存在/缺失、曲线点、训练分趋势跳过历史密钥）、
 * 导出快照（属主解密全量、逐类降级占位文案）、一次性链接的属主断言三态（越权不消耗 / 过期 / 领取即焚）。
 *
 * <p>导出链路的每一处 try/catch 都是"密钥销毁后仍要给得出东西"的降级承诺，必须逐条钉住；
 * 只有真跑到 catch 才算覆盖，无异常的正常路径不算。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArchiveServiceTest {

    @Mock ReportRepository reportRepo;
    @Mock EmotionTrajectoryRepository trajRepo;
    @Mock EmotionProfileRepository profileRepo;
    @Mock RiskEventRepository riskEventRepo;
    @Mock ExerciseRecordRepository exerciseRepo;
    @Mock DiaryRepository diaryRepo;
    @Mock CompanionSessionRepository companionSessionRepo;
    @Mock CompanionTurnRepository companionTurnRepo;
    @Mock MoodCheckInRepository checkInRepo;
    @Mock GrowthLetterRepository letterRepo;
    @Mock ReadingService reading;
    @Mock ExportRecordRepository exportRecords;
    @Mock ExportStore exportStore;
    @Mock UserRepository userRepo;
    @Mock CryptoService crypto;
    @Mock AuditService audit;

    private final ObjectMapper mapper = new ObjectMapper();
    private final BusinessCalendar cal = new BusinessCalendar("Asia/Shanghai");

    private ArchiveService service;

    @BeforeEach
    void setUp() {
        service = new ArchiveService(reportRepo, trajRepo, profileRepo, riskEventRepo, exerciseRepo, cal,
                diaryRepo, companionSessionRepo, companionTurnRepo, checkInRepo, letterRepo, reading,
                exportRecords, exportStore, userRepo, crypto, audit, mapper);
    }

    // ---------------- helpers ----------------

    private String weekOf(LocalDate d) {
        WeekFields wf = WeekFields.ISO;
        return d.get(wf.weekBasedYear()) + "-W" + String.format("%02d", d.get(wf.weekOfWeekBasedYear()));
    }

    /** 所有集合型依赖返回空，聚焦被测的那一支 */
    private void stubEmptyRepos() {
        when(reportRepo.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(reportRepo.findByUserIdAndTypeAndDeletedAtIsNull(anyLong(), anyString())).thenReturn(List.of());
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of());
        when(profileRepo.findByUserIdOrderByStatWeekDesc(anyLong())).thenReturn(List.of());
        when(diaryRepo.findByUserIdAndDeletedAtIsNullOrderByRecordDateDescIdDesc(anyLong())).thenReturn(List.of());
        when(exerciseRepo.findByUserIdOrderByCreatedAtDescIdDesc(anyLong())).thenReturn(List.of());
        when(riskEventRepo.findByUserIdOrderByCreatedAtDesc(anyLong())).thenReturn(List.of());
        when(companionSessionRepo.findByUserIdOrderByChatDateDescIdDesc(anyLong(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(checkInRepo.findByUserIdOrderByCheckDateDesc(anyLong())).thenReturn(List.of());
        when(letterRepo.findByUserIdOrderByStatWeekDesc(anyLong())).thenReturn(List.of());
        when(reading.favoritesForExport(anyLong())).thenReturn(List.of());
    }

    private ReportEntity report(long id, String type, Instant createdAt) {
        ReportEntity r = new ReportEntity();
        r.setId(id);
        r.setUserId(1L);
        r.setType(type);
        r.setTitle("报告" + id);
        r.setRiskLevel("LOW");
        r.setCreatedAt(createdAt);
        r.setStarred((short) 0);
        return r;
    }

    private EmotionTrajectoryEntity traj(LocalDate d) {
        EmotionTrajectoryEntity t = new EmotionTrajectoryEntity();
        t.setUserId(1L);
        t.setRecordDate(d);
        t.setPrimaryEmotion("焦虑");
        t.setValence(new BigDecimal("-0.400"));
        t.setIntensity(new BigDecimal("0.700"));
        t.setSourceType("DIARY");
        return t;
    }

    // ---------------- weeklySummary ----------------

    @Test
    @DisplayName("weeklySummary：画像齐备时输出效价、风险档与压力源/误区，null 字段退回空数组")
    void weeklySummaryIncludesProfileAndPoints() {
        LocalDate date = LocalDate.of(2026, 9, 16);
        String statWeek = weekOf(date);

        EmotionProfileEntity p = new EmotionProfileEntity();
        p.setStatWeek(statWeek);
        p.setAvgValence(new BigDecimal("0.125"));
        p.setRiskLevel("MEDIUM");
        p.setStressorTopJson("[{\"name\":\"学业压力\"}]");
        p.setDistortionTopJson(null);
        when(profileRepo.findByUserIdAndStatWeek(1L, statWeek)).thenReturn(Optional.of(p));
        // 注意：stubEmptyRepos 也桩了 trajRepo 的同一方法，必须放在它之后声明才能生效
        stubEmptyRepos();
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of(traj(date)));

        ObjectNode root = service.weeklySummary(1L, date);

        assertEquals(statWeek, root.path("statWeek").asText());
        assertEquals("0.125", root.path("profile").path("avgValence").asText());
        assertEquals("MEDIUM", root.path("profile").path("riskLevel").asText());
        assertEquals(1, root.path("profile").path("stressorTop").size());
        assertEquals(0, root.path("profile").path("distortionTop").size(), "null 走空数组兜底");
        assertEquals(1, root.path("emotionPoints").size());
        assertEquals("焦虑", root.path("emotionPoints").get(0).path("emotion").asText());
        assertEquals("-0.400", root.path("emotionPoints").get(0).path("valence").asText());
    }

    @Test
    @DisplayName("weeklySummary：本周无画像时风险档回落到 LOW，不虚报数据")
    void weeklySummaryDefaultsRiskLevelWithoutProfile() {
        LocalDate date = LocalDate.of(2026, 9, 16);
        when(profileRepo.findByUserIdAndStatWeek(anyLong(), anyString())).thenReturn(Optional.empty());
        stubEmptyRepos();

        ObjectNode root = service.weeklySummary(1L, date);

        assertEquals("LOW", root.path("profile").path("riskLevel").asText());
        assertTrue(root.path("profile").path("avgValence").isMissingNode());
    }

    @Test
    @DisplayName("weeklySummary：压力源 JSON 损坏时退回空数组而不是整页失败")
    void weeklySummarySurvivesBrokenStressorJson() {
        LocalDate date = LocalDate.of(2026, 9, 16);
        EmotionProfileEntity p = new EmotionProfileEntity();
        p.setStatWeek(weekOf(date));
        p.setRiskLevel("LOW");
        p.setStressorTopJson("{ 坏 JSON");
        when(profileRepo.findByUserIdAndStatWeek(anyLong(), anyString())).thenReturn(Optional.of(p));
        stubEmptyRepos();

        ObjectNode root = service.weeklySummary(1L, date);

        assertEquals(0, root.path("profile").path("stressorTop").size());
    }

    @Test
    @DisplayName("weeklySummary：训练分趋势跳过历史密钥解不开的报告，其余照常产出")
    void weeklySummaryCollectsTrainingScoresAndSkipsUndecryptable() {
        LocalDate date = LocalDate.of(2026, 9, 16);
        when(profileRepo.findByUserIdAndStatWeek(anyLong(), anyString())).thenReturn(Optional.empty());
        stubEmptyRepos();

        ReportEntity ok = report(1L, "SIMULATE", Instant.parse("2026-09-16T10:00:00Z"));
        ok.setContentEnc("ok".getBytes());
        ReportEntity bad = report(2L, "SIMULATE", Instant.parse("2026-09-17T10:00:00Z"));
        bad.setContentEnc("bad".getBytes());
        when(reportRepo.findByUserIdAndTypeAndDeletedAtIsNull(1L, "SIMULATE")).thenReturn(List.of(ok, bad));
        when(crypto.decryptUserField(1L, "ok".getBytes())).thenReturn("{\"overall\":{\"avgScore\":88}}");
        when(crypto.decryptUserField(1L, "bad".getBytes())).thenThrow(new IllegalStateException("key destroyed"));

        ObjectNode root = service.weeklySummary(1L, date);

        assertEquals(1, root.path("trainingScores").size());
        assertEquals(88, root.path("trainingScores").get(0).path("avgScore").asInt());
        assertEquals("2026-09-16", root.path("trainingScores").get(0).path("date").asText());
    }

    @Test
    @DisplayName("weeklySummary：周内报告只留区间内的，createdAt 为空的按本次运行内保留")
    void weeklySummaryFiltersReportsByRange() {
        LocalDate date = LocalDate.of(2026, 9, 16);
        LocalDate from = date.with(WeekFields.ISO.dayOfWeek(), 1);
        when(profileRepo.findByUserIdAndStatWeek(anyLong(), anyString())).thenReturn(Optional.empty());
        stubEmptyRepos();

        ReportEntity inside = report(1L, "DIARY", Instant.parse("2026-09-16T10:00:00Z"));
        ReportEntity outside = report(2L, "DIARY", Instant.parse("2020-01-01T10:00:00Z"));
        ReportEntity noDate = report(3L, "DIARY", null);
        when(reportRepo.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(new PageImpl<>(List.of(inside, outside, noDate)));

        ObjectNode root = service.weeklySummary(1L, date);

        assertEquals(2, root.path("reports").size(), "区间外的报告被滤掉");
        assertEquals(from.toString(), root.path("reports").get(1).path("date").asText());
    }

    // ---------------- createExport / downloadExport ----------------

    @Test
    @DisplayName("createExport：快照带昵称，落一次性暂存并留痕审计")
    void createExportSnapshotsAndAudits() {
        UserEntity u = new UserEntity();
        u.setId(1L);
        u.setNickname("小岛");
        when(userRepo.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(u));
        stubEmptyRepos();

        String fileId = service.createExport(1L, "127.0.0.1");

        assertNotNull(fileId);
        ArgumentCaptor<ObjectNode> sc = ArgumentCaptor.forClass(ObjectNode.class);
        verify(exportStore).put(eq(fileId), eq(1L), sc.capture());
        assertEquals("小岛", sc.getValue().path("nickname").asText());
        assertTrue(sc.getValue().has("generatedAt"));
        assertEquals(0, sc.getValue().path("reports").size());
        verify(exportRecords).save(any(ExportRecordEntity.class));
        verify(audit).record(1L, "EXPORT", "archive_export:" + fileId, "127.0.0.1");
    }

    @Test
    @DisplayName("createExport：用户行缺失时快照仍可生成，不写昵称字段")
    void createExportWithoutUserRow() {
        when(userRepo.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        stubEmptyRepos();

        String fileId = service.createExport(1L, null);

        ArgumentCaptor<ObjectNode> sc = ArgumentCaptor.forClass(ObjectNode.class);
        verify(exportStore).put(eq(fileId), eq(1L), sc.capture());
        assertTrue(sc.getValue().path("nickname").isMissingNode());
    }

    @Test
    @DisplayName("createExport：进程内暂存实现下顺带清扫过期快照")
    void createExportPurgesExpiredInMemorySnapshots() {
        InMemoryExportStore real = new InMemoryExportStore();
        ArchiveService local = new ArchiveService(reportRepo, trajRepo, profileRepo, riskEventRepo,
                exerciseRepo, cal, diaryRepo, companionSessionRepo, companionTurnRepo, checkInRepo,
                letterRepo, reading, exportRecords, real, userRepo, crypto, audit, mapper);
        when(userRepo.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        stubEmptyRepos();

        String fileId = local.createExport(1L, "ip");

        assertEquals(ExportStore.Outcome.CLAIMED, real.take(fileId, 1L).outcome());
        assertEquals(ExportStore.Outcome.MISSING, real.take(fileId, 1L).outcome(), "领取即焚");
        verify(exportRecords).markExpired(any(Instant.class));
    }

    @Test
    @DisplayName("downloadExport：他人持链接领取得到 403，且不消耗属主的链接")
    void downloadExportRejectsForeignClaim() {
        when(exportStore.take("f1", 2L)).thenReturn(ExportStore.Taken.FOREIGN);

        BizException ex = assertThrows(BizException.class, () -> service.downloadExport(2L, "f1", "ip"));

        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("downloadExport：链接过期或不存在时给出可读的 404")
    void downloadExportReportsMissingLink() {
        when(exportStore.take("f1", 1L)).thenReturn(ExportStore.Taken.MISSING);

        BizException ex = assertThrows(BizException.class, () -> service.downloadExport(1L, "f1", "ip"));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("限时 5 分钟"));
    }

    @Test
    @DisplayName("downloadExport：属主领取成功，导出留痕置为已领取并落审计")
    void downloadExportMarksClaimed() {
        ObjectNode data = mapper.createObjectNode().put("scope", "ARCHIVE");
        when(exportStore.take("f1", 1L)).thenReturn(new ExportStore.Taken(ExportStore.Outcome.CLAIMED, data));
        ExportRecordEntity rec = new ExportRecordEntity();
        rec.setStatus("PENDING");
        when(exportRecords.findByFileRef("f1")).thenReturn(Optional.of(rec));

        ObjectNode out = service.downloadExport(1L, "f1", "10.0.0.1");

        assertEquals("ARCHIVE", out.path("scope").asText());
        assertEquals("CLAIMED", rec.getStatus());
        assertNotNull(rec.getClaimedAt());
        verify(exportRecords).save(rec);
        verify(audit).record(1L, "EXPORT_DOWNLOAD", "archive_export:f1", "10.0.0.1");
    }

    @Test
    @DisplayName("downloadExport：没有留痕行时不影响下载本身")
    void downloadExportToleratesMissingRecord() {
        ObjectNode data = mapper.createObjectNode();
        when(exportStore.take("f1", 1L)).thenReturn(new ExportStore.Taken(ExportStore.Outcome.CLAIMED, data));
        when(exportRecords.findByFileRef("f1")).thenReturn(Optional.empty());

        assertNotNull(service.downloadExport(1L, "f1", null));
    }

    // ---------------- createDataExport ----------------

    @Test
    @DisplayName("createDataExport：S2 可携带权的十类数据段齐全")
    void createDataExportCoversAllSections() {
        UserEntity u = new UserEntity();
        u.setId(1L);
        u.setNickname("小岛");
        when(userRepo.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(u));
        stubEmptyRepos();

        String fileId = service.createDataExport(1L, "ip");

        ArgumentCaptor<ObjectNode> sc = ArgumentCaptor.forClass(ObjectNode.class);
        verify(exportStore).put(eq(fileId), eq(1L), sc.capture());
        ObjectNode snap = sc.getValue();
        assertEquals("PERSONAL_DATA", snap.path("scope").asText());
        for (String section : List.of("reports", "diaries", "emotionPoints", "profiles", "exerciseRecords",
                "riskEvents", "companion", "moodCheckIns", "growthLetters", "favorites")) {
            assertTrue(snap.has(section), "缺少数据段: " + section);
        }
        verify(audit).record(1L, "DATA_EXPORT", "personal_data_export:" + fileId, "ip");
    }

    // ---------------- 逐类导出与解密降级 ----------------

    @Test
    @DisplayName("报告导出：反馈原话解密失败给占位，正文解不开也给占位，都不炸整包")
    void exportedReportsDegradePerField() {
        when(userRepo.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        stubEmptyRepos();

        ReportEntity r = report(1L, "DIARY", Instant.parse("2026-09-16T10:00:00Z"));
        r.setStarred((short) 1);
        r.setFeedback("USEFUL");
        r.setFeedbackNoteEnc("fn".getBytes());
        r.setContentEnc("bad".getBytes());
        when(reportRepo.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(new PageImpl<>(List.of(r)));
        when(crypto.decryptUserField(1L, "fn".getBytes())).thenThrow(new IllegalStateException("key"));
        when(crypto.decryptUserField(1L, "bad".getBytes())).thenThrow(new IllegalStateException("key"));

        service.createExport(1L, "ip");

        ArgumentCaptor<ObjectNode> sc = ArgumentCaptor.forClass(ObjectNode.class);
        verify(exportStore).put(anyString(), eq(1L), sc.capture());
        JsonNode exported = sc.getValue().path("reports").get(0);
        assertEquals(true, exported.path("starred").asBoolean());
        assertEquals("USEFUL", exported.path("feedback").asText());
        assertTrue(exported.path("feedbackNote").asText().contains("密钥已销毁"));
        assertTrue(exported.path("content").asText().contains("密钥已销毁"));
    }

    @Test
    @DisplayName("报告导出：无反馈的报告不写反馈字段，避免空白噪声")
    void exportedReportsSkipNullFeedback() {
        when(userRepo.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        stubEmptyRepos();

        ReportEntity r = report(1L, "DIARY", Instant.parse("2026-09-16T10:00:00Z"));
        r.setContentEnc("ok".getBytes());
        when(reportRepo.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(anyLong(), any()))
                .thenReturn(new PageImpl<>(List.of(r)));
        when(crypto.decryptUserField(1L, "ok".getBytes())).thenReturn("{\"a\":1}");

        service.createExport(1L, "ip");

        ArgumentCaptor<ObjectNode> sc = ArgumentCaptor.forClass(ObjectNode.class);
        verify(exportStore).put(anyString(), eq(1L), sc.capture());
        JsonNode exported = sc.getValue().path("reports").get(0);
        assertFalse(exported.has("feedback"));
        assertEquals(1, exported.path("content").path("a").asInt());
    }

    @Test
    @DisplayName("日记导出：自评分为空时不写该字段，解不开给占位")
    void exportedDiariesDegrade() {
        when(userRepo.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        stubEmptyRepos();

        DiaryEntity ok = new DiaryEntity();
        ok.setUserId(1L);
        ok.setRecordDate(LocalDate.of(2026, 9, 16));
        ok.setMoodSelfRating((short) 4);
        ok.setContentEnc("ok".getBytes());

        DiaryEntity bad = new DiaryEntity();
        bad.setUserId(1L);
        bad.setRecordDate(LocalDate.of(2026, 9, 15));
        bad.setContentEnc("bad".getBytes());

        when(diaryRepo.findByUserIdAndDeletedAtIsNullOrderByRecordDateDescIdDesc(1L)).thenReturn(List.of(ok, bad));
        when(crypto.decryptUserField(1L, "ok".getBytes())).thenReturn("今天还不错");
        when(crypto.decryptUserField(1L, "bad".getBytes())).thenThrow(new IllegalStateException("key"));

        service.createExport(1L, "ip");

        ArgumentCaptor<ObjectNode> sc = ArgumentCaptor.forClass(ObjectNode.class);
        verify(exportStore).put(anyString(), eq(1L), sc.capture());
        var diaries = sc.getValue().path("diaries");
        assertEquals("今天还不错", diaries.get(0).path("content").asText());
        assertEquals(4, diaries.get(0).path("moodSelfRating").asInt());
        assertFalse(diaries.get(1).has("moodSelfRating"));
        assertTrue(diaries.get(1).path("content").asText().contains("密钥已销毁"));
    }

    @Test
    @DisplayName("情绪曲线导出：附上事件标签数组，标签非法时退回空数组")
    void exportedTrajectoryIncludesEventTags() {
        when(userRepo.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        stubEmptyRepos();

        EmotionTrajectoryEntity withTags = traj(LocalDate.of(2026, 9, 16));
        withTags.setEventTags("[\"学业压力\"]");
        EmotionTrajectoryEntity brokenTags = traj(LocalDate.of(2026, 9, 15));
        brokenTags.setEventTags("{ 坏");
        when(trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(anyLong(), any(), any()))
                .thenReturn(List.of(withTags, brokenTags));

        service.createExport(1L, "ip");

        ArgumentCaptor<ObjectNode> sc = ArgumentCaptor.forClass(ObjectNode.class);
        verify(exportStore).put(anyString(), eq(1L), sc.capture());
        var pts = sc.getValue().path("emotionPoints");
        assertEquals(1, pts.get(0).path("eventTags").size());
        assertEquals(0, pts.get(1).path("eventTags").size());
    }

    @Test
    @DisplayName("画像导出：效价为空时不写该字段")
    void exportedProfilesSkipNullValence() {
        when(userRepo.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        stubEmptyRepos();

        EmotionProfileEntity p = new EmotionProfileEntity();
        p.setUserId(1L);
        p.setStatWeek("2026-W38");
        p.setRiskLevel("LOW");
        p.setAvgValence(null);
        when(profileRepo.findByUserIdOrderByStatWeekDesc(1L)).thenReturn(List.of(p));

        service.createExport(1L, "ip");

        ArgumentCaptor<ObjectNode> sc = ArgumentCaptor.forClass(ObjectNode.class);
        verify(exportStore).put(anyString(), eq(1L), sc.capture());
        assertFalse(sc.getValue().path("profiles").get(0).has("avgValence"));
    }

    @Test
    @DisplayName("练习记录导出：空反馈与空时间回落到空串")
    void exportedExercisesFallBackToBlankStrings() {
        when(userRepo.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        stubEmptyRepos();

        ExerciseRecordEntity x = new ExerciseRecordEntity();
        x.setUserId(1L);
        x.setExerciseCode("ex_478_breath");
        x.setCompleted((short) 1);
        x.setFeedback(null);
        x.setCreatedAt(null);
        when(exerciseRepo.findByUserIdOrderByCreatedAtDescIdDesc(1L)).thenReturn(List.of(x));

        service.createDataExport(1L, "ip");

        ArgumentCaptor<ObjectNode> sc = ArgumentCaptor.forClass(ObjectNode.class);
        verify(exportStore).put(anyString(), eq(1L), sc.capture());
        JsonNode exported = sc.getValue().path("exerciseRecords").get(0);
        assertEquals("", exported.path("feedback").asText());
        assertEquals("", exported.path("date").asText());
        assertEquals(1, exported.path("completed").asInt());
    }

    @Test
    @DisplayName("风险事件导出：无证据时写 null，解不开给占位，规则码为空回落空串")
    void exportedRiskEventsDegrade() {
        when(userRepo.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        stubEmptyRepos();

        RiskEventEntity noEvidence = new RiskEventEntity();
        noEvidence.setId(1L);
        noEvidence.setLevel("HIGH");
        noEvidence.setTriggerType("KEYWORD_RULE");
        noEvidence.setRuleCode(null);
        noEvidence.setActionTaken("CRISIS_CARD");
        noEvidence.setEvidenceRefEnc(null);
        noEvidence.setCreatedAt(null);

        RiskEventEntity brokenEvidence = new RiskEventEntity();
        brokenEvidence.setId(2L);
        brokenEvidence.setLevel("MEDIUM");
        brokenEvidence.setTriggerType("LLM_SEMANTIC");
        brokenEvidence.setRuleCode("R1");
        brokenEvidence.setActionTaken("PROFILE_FLAG");
        brokenEvidence.setEvidenceRefEnc("ev".getBytes());
        brokenEvidence.setCreatedAt(Instant.parse("2026-09-16T10:00:00Z"));

        when(riskEventRepo.findByUserIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(noEvidence, brokenEvidence));
        when(crypto.decryptUserField(1L, "ev".getBytes())).thenThrow(new IllegalStateException("key"));

        service.createDataExport(1L, "ip");

        ArgumentCaptor<ObjectNode> sc = ArgumentCaptor.forClass(ObjectNode.class);
        verify(exportStore).put(anyString(), eq(1L), sc.capture());
        var events = sc.getValue().path("riskEvents");
        assertTrue(events.get(0).path("evidence").isNull());
        assertEquals("", events.get(0).path("ruleCode").asText());
        assertTrue(events.get(1).path("evidence").asText().contains("密钥已销毁"));
    }

    @Test
    @DisplayName("漫聊导出：逐轮用户消息解密失败给占位，AI 文本原样带出")
    void exportedCompanionTurnsDegrade() {
        when(userRepo.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        stubEmptyRepos();

        CompanionSessionEntity s = new CompanionSessionEntity();
        s.setId(7L);
        s.setUserId(1L);
        s.setChatDate(LocalDate.of(2026, 9, 16));
        s.setSegmentNo(1);
        s.setStatus("DIGESTED");
        s.setTurns(2);
        when(companionSessionRepo.findByUserIdOrderByChatDateDescIdDesc(anyLong(), any()))
                .thenReturn(new PageImpl<>(List.of(s)));

        CompanionTurnEntity t = new CompanionTurnEntity();
        t.setId(1L);
        t.setSessionId(7L);
        t.setTurnNo(1);
        t.setAiText("我在听");
        t.setNoAnalyze((short) 1);
        t.setUserTextEnc("bad".getBytes());
        when(companionTurnRepo.findBySessionIdOrderByTurnNoAsc(7L)).thenReturn(List.of(t));
        when(crypto.decryptUserField(1L, "bad".getBytes())).thenThrow(new IllegalStateException("key"));

        service.createDataExport(1L, "ip");

        ArgumentCaptor<ObjectNode> sc = ArgumentCaptor.forClass(ObjectNode.class);
        verify(exportStore).put(anyString(), eq(1L), sc.capture());
        JsonNode session = sc.getValue().path("companion").get(0);
        assertEquals(1, session.path("segmentNo").asInt());
        JsonNode turn = session.path("turnList").get(0);
        assertEquals("我在听", turn.path("aiText").asText());
        assertEquals(true, turn.path("noAnalyze").asBoolean());
        assertTrue(turn.path("userText").asText().contains("密钥已销毁"));
    }

    @Test
    @DisplayName("打卡导出：无一句话写 null，评分与精力为空不写该字段")
    void exportedCheckInsDegrade() {
        when(userRepo.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        stubEmptyRepos();

        MoodCheckInEntity noNote = new MoodCheckInEntity();
        noNote.setUserId(1L);
        noNote.setCheckDate(LocalDate.of(2026, 9, 16));
        noNote.setEmotionCode("CALM");
        noNote.setRating(null);
        noNote.setEnergy(null);
        noNote.setNoteEnc(null);

        MoodCheckInEntity brokenNote = new MoodCheckInEntity();
        brokenNote.setUserId(1L);
        brokenNote.setCheckDate(LocalDate.of(2026, 9, 15));
        brokenNote.setEmotionCode("JOY");
        brokenNote.setRating((short) 5);
        brokenNote.setEnergy((short) 3);
        brokenNote.setNoteEnc("bad".getBytes());

        when(checkInRepo.findByUserIdOrderByCheckDateDesc(1L)).thenReturn(List.of(noNote, brokenNote));
        when(crypto.decryptUserField(1L, "bad".getBytes())).thenThrow(new IllegalStateException("key"));

        service.createDataExport(1L, "ip");

        ArgumentCaptor<ObjectNode> sc = ArgumentCaptor.forClass(ObjectNode.class);
        verify(exportStore).put(anyString(), eq(1L), sc.capture());
        var checkIns = sc.getValue().path("moodCheckIns");
        assertTrue(checkIns.get(0).path("note").isNull());
        assertFalse(checkIns.get(0).has("rating"));
        assertFalse(checkIns.get(0).has("energy"));
        assertEquals(5, checkIns.get(1).path("rating").asInt());
        assertEquals(3, checkIns.get(1).path("energy").asInt());
        assertTrue(checkIns.get(1).path("note").asText().contains("密钥已销毁"));
    }

    @Test
    @DisplayName("成长来信导出：正文解不开给占位")
    void exportedLettersDegrade() {
        when(userRepo.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.empty());
        stubEmptyRepos();

        GrowthLetterEntity l = new GrowthLetterEntity();
        l.setId(1L);
        l.setUserId(1L);
        l.setStatWeek("2026-W38");
        l.setContentEnc("bad".getBytes());
        when(letterRepo.findByUserIdOrderByStatWeekDesc(1L)).thenReturn(List.of(l));
        when(crypto.decryptUserField(1L, "bad".getBytes())).thenThrow(new IllegalStateException("key"));

        service.createDataExport(1L, "ip");

        ArgumentCaptor<ObjectNode> sc = ArgumentCaptor.forClass(ObjectNode.class);
        verify(exportStore).put(anyString(), eq(1L), sc.capture());
        JsonNode letter = sc.getValue().path("growthLetters").get(0);
        assertEquals("2026-W38", letter.path("statWeek").asText());
        assertTrue(letter.path("content").asText().contains("密钥已销毁"));
    }
}
