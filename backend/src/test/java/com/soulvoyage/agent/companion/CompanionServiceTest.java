package com.soulvoyage.agent.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.audit.AuditService;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.state.SlidingWindowLimiter;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.achievement.AchievementService;
import com.soulvoyage.domain.companion.CompanionSessionEntity;
import com.soulvoyage.domain.companion.CompanionSessionRepository;
import com.soulvoyage.domain.companion.CompanionTurnEntity;
import com.soulvoyage.domain.companion.CompanionTurnRepository;
import com.soulvoyage.domain.crisis.CrisisService;
import com.soulvoyage.domain.notify.UserPreferencesEntity;
import com.soulvoyage.domain.notify.UserPreferencesRepository;
import com.soulvoyage.domain.risk.RiskEventEntity;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.task.TaskInstanceEntity;
import com.soulvoyage.llm.LlmClient;
import com.soulvoyage.llm.OutputValidator;
import com.soulvoyage.llm.PromptTemplates;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 树洞漫聊会话引擎的分支覆盖：分段生命周期（首段/续段/静默封段/复用）、四道入口闸门
 * （属主与状态、空白与超长、用户级限流、200 轮软上限）、危机一票当轮替换且不经 LLM、
 * LLM 失败降级模板句、防依赖引导触发条件、封段消化的可分析判据与 wantTrace 阈值。
 *
 * <p>这一层的每条分支都是"护栏"或"降级承诺"：护栏漏测意味着可以在生产被绕过，
 * 降级漏测意味着一次外部故障会把陪伴链路整条打断，因此逐条都要真的走到。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanionServiceTest {

    @Mock CompanionSessionRepository sessionRepo;
    @Mock CompanionTurnRepository turnRepo;
    @Mock UserPreferencesRepository prefsRepo;
    @Mock RiskEventRepository riskEventRepo;
    @Mock CryptoService crypto;
    @Mock LlmClient llm;
    @Mock PromptTemplates templates;
    @Mock OutputValidator validator;
    @Mock OrchestratorService orchestrator;
    @Mock CrisisService crisisService;
    @Mock AuditService audit;
    @Mock CompanionContextAssembler context;
    @Mock AchievementService achievements;
    @Mock SlidingWindowLimiter windowLimiter;

    private final ObjectMapper mapper = new ObjectMapper();
    private final BusinessCalendar cal = new BusinessCalendar("Asia/Shanghai");

    private CompanionService service;

    @BeforeEach
    void setUp() {
        service = new CompanionService(sessionRepo, turnRepo, prefsRepo, riskEventRepo, crypto, llm,
                templates, validator, mapper, cal, orchestrator, crisisService, audit, context,
                achievements, windowLimiter);
        when(windowLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
        when(sessionRepo.sumTurnsOnDate(anyLong(), any())).thenReturn(0L);
        when(crypto.encryptUserField(anyLong(), anyString())).thenReturn("enc".getBytes());
        when(crypto.decryptUserField(anyLong(), any())).thenReturn("");
        // 回填 id：SessionView 构造会拆箱 getId()，不补会 NPE（真实 save 由 JPA 生成主键）
        when(sessionRepo.save(any(CompanionSessionEntity.class))).thenAnswer(inv -> {
            CompanionSessionEntity s = inv.getArgument(0);
            if (s.getId() == null) s.setId(99L);
            return s;
        });
        when(turnRepo.save(any(CompanionTurnEntity.class))).thenAnswer(inv -> {
            CompanionTurnEntity t = inv.getArgument(0);
            if (t.getId() == null) t.setId(77L);
            return t;
        });
    }

    // ---------------- helpers ----------------

    private CompanionSessionEntity session(long id, long userId, String status, int turns) {
        CompanionSessionEntity s = new CompanionSessionEntity();
        s.setId(id);
        s.setUserId(userId);
        s.setChatDate(cal.today());
        s.setSegmentNo(1);
        s.setStatus(status);
        s.setTurns(turns);
        s.setLastTurnAt(Instant.now());
        s.setExclFlag((short) 0);
        return s;
    }

    private CompanionTurnEntity turn(long sessionId, int turnNo, String plain) {
        CompanionTurnEntity t = new CompanionTurnEntity();
        t.setId((long) (100 + turnNo));
        t.setSessionId(sessionId);
        t.setTurnNo(turnNo);
        t.setUserTextEnc((plain + "-enc").getBytes());
        t.setAiText("我在听");
        t.setMoodTag("FOLLOW");
        t.setRiskHit((short) 0);
        t.setNoAnalyze((short) 0);
        return t;
    }

    private UserPreferencesEntity prefs(short analysisOn) {
        UserPreferencesEntity p = new UserPreferencesEntity();
        p.setUserId(1L);
        p.setCompanionAnalysisOn(analysisOn);
        return p;
    }

    private TaskInstanceEntity task(String taskNo) {
        TaskInstanceEntity t = new TaskInstanceEntity();
        t.setTaskNo(taskNo);
        return t;
    }

    private void stubActiveSession() {
        when(sessionRepo.findByIdAndUserId(9L, 1L))
                .thenReturn(Optional.of(session(9L, 1L, "ACTIVE", 0)));
    }

    private void stubNormalReply(String reply) {
        when(templates.system("companion_v1")).thenReturn("SYS");
        when(templates.render(anyString(), any())).thenReturn("SYS-R");
        when(context.assemble(1L))
                .thenReturn(new CompanionContextAssembler.ProfileBlock("", null));
        when(llm.stream(any(), any())).thenAnswer(inv -> {
            LlmClient.TokenSink sink = inv.getArgument(1);
            sink.onDelta("{\"reply\":\"" + reply + "\"}");
            return new LlmClient.LlmResponse("{\"reply\":\"" + reply + "\"}", "mock", 1, 1, 1L);
        });
        when(validator.validate(anyString(), anyString()))
                .thenReturn(mapper.createObjectNode().put("reply", reply));
    }

    // ---------------- openOrReuse / activeView / history ----------------

    @Test
    @DisplayName("openOrReuse：今日无会话时开第一段，并按偏好开关决定是否排除分析")
    void openOrReuseCreatesFirstSegment() {
        when(sessionRepo.findByUserIdAndChatDateOrderBySegmentNoDesc(1L, cal.today())).thenReturn(List.of());
        when(prefsRepo.findByUserId(1L)).thenReturn(Optional.of(prefs((short) 1)));
        when(sessionRepo.save(any(CompanionSessionEntity.class))).thenAnswer(inv -> {
            CompanionSessionEntity s = inv.getArgument(0);
            s.setId(11L);
            return s;
        });

        CompanionService.SessionView v = service.openOrReuse(1L);

        assertEquals(11L, v.sessionId());
        assertEquals(1, v.segmentNo());
        assertEquals("ACTIVE", v.status());
        assertEquals(0, v.turns());
        assertTrue(v.turnList().isEmpty(), "首次打开不带历史逐轮");
    }

    @Test
    @DisplayName("openOrReuse：分析总开关关闭时新段直接标记为排除")
    void openOrReuseMarksExcludedWhenAnalysisOff() {
        when(sessionRepo.findByUserIdAndChatDateOrderBySegmentNoDesc(1L, cal.today())).thenReturn(List.of());
        when(prefsRepo.findByUserId(1L)).thenReturn(Optional.of(prefs((short) 0)));

        service.openOrReuse(1L);

        ArgumentCaptor<CompanionSessionEntity> c = ArgumentCaptor.forClass(CompanionSessionEntity.class);
        verify(sessionRepo).save(c.capture());
        assertEquals((short) 1, c.getValue().getExclFlag());
    }

    @Test
    @DisplayName("openOrReuse：缺偏好行时按默认开启处理，不误排除")
    void openOrReuseDefaultsToAnalysisOn() {
        when(sessionRepo.findByUserIdAndChatDateOrderBySegmentNoDesc(1L, cal.today())).thenReturn(List.of());
        when(prefsRepo.findByUserId(1L)).thenReturn(Optional.empty());

        service.openOrReuse(1L);

        ArgumentCaptor<CompanionSessionEntity> c = ArgumentCaptor.forClass(CompanionSessionEntity.class);
        verify(sessionRepo).save(c.capture());
        assertEquals((short) 0, c.getValue().getExclFlag());
    }

    @Test
    @DisplayName("openOrReuse：今日已有未静默的活跃段时直接复用，不新开")
    void openOrReuseReusesFreshSegment() {
        when(sessionRepo.findByUserIdAndChatDateOrderBySegmentNoDesc(1L, cal.today()))
                .thenReturn(List.of(session(10L, 1L, "ACTIVE", 3)));

        CompanionService.SessionView v = service.openOrReuse(1L);

        assertEquals(10L, v.sessionId());
        verify(sessionRepo, never()).save(any(CompanionSessionEntity.class));
    }

    @Test
    @DisplayName("openOrReuse：活跃段静默超 30 分钟时先封口消化，再另起新段")
    void openOrReuseSealsSilentSegment() {
        CompanionSessionEntity stale = session(10L, 1L, "ACTIVE", 3);
        stale.setLastTurnAt(Instant.now().minus(Duration.ofMinutes(61)));
        when(sessionRepo.findByUserIdAndChatDateOrderBySegmentNoDesc(1L, cal.today()))
                .thenReturn(List.of(stale));
        when(turnRepo.findBySessionIdAndNoAnalyzeOrderByTurnNoAsc(10L, (short) 0)).thenReturn(List.of());
        when(prefsRepo.findByUserId(1L)).thenReturn(Optional.of(prefs((short) 1)));

        CompanionService.SessionView v = service.openOrReuse(1L);

        assertEquals("DIGESTED", stale.getStatus());
        assertEquals(2, v.segmentNo(), "新段序号接在旧段之后");
    }

    @Test
    @DisplayName("openOrReuse：段号按当日最大段号递增，不从头数")
    void openOrReuseIncrementsSegmentNo() {
        CompanionSessionEntity latest = session(10L, 1L, "DIGESTED", 5);
        latest.setSegmentNo(4);
        when(sessionRepo.findByUserIdAndChatDateOrderBySegmentNoDesc(1L, cal.today()))
                .thenReturn(List.of(latest));
        when(prefsRepo.findByUserId(1L)).thenReturn(Optional.of(prefs((short) 1)));

        assertEquals(5, service.openOrReuse(1L).segmentNo());
    }

    @Test
    @DisplayName("activeView：没有活跃段时返回 null")
    void activeViewReturnsNullWhenNoActive() {
        when(sessionRepo.findByUserIdAndChatDateAndStatus(1L, cal.today(), "ACTIVE"))
                .thenReturn(Optional.empty());

        assertNull(service.activeView(1L));
    }

    @Test
    @DisplayName("activeView：有活跃段时带上逐轮回放")
    void activeViewIncludesTurns() {
        when(sessionRepo.findByUserIdAndChatDateAndStatus(1L, cal.today(), "ACTIVE"))
                .thenReturn(Optional.of(session(10L, 1L, "ACTIVE", 1)));
        when(turnRepo.findBySessionIdOrderByTurnNoAsc(10L)).thenReturn(List.of(turn(10L, 1, "在吗")));
        when(crypto.decryptUserField(1L, "在吗-enc".getBytes())).thenReturn("在吗");

        CompanionService.SessionView v = service.activeView(1L);

        assertEquals(1, v.turnList().size());
        assertEquals("在吗", v.turnList().get(0).userText());
    }

    // ---------------- turn 入口闸门 ----------------

    @Test
    @DisplayName("turn：会话不存在或非属主一律 404")
    void turnRejectsUnknownSession() {
        when(sessionRepo.findByIdAndUserId(9L, 1L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class, () -> service.turn(1L, 9L, "你好"));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("turn：已收起的段拒绝继续发送")
    void turnRejectsSealedSession() {
        when(sessionRepo.findByIdAndUserId(9L, 1L))
                .thenReturn(Optional.of(session(9L, 1L, "DIGESTED", 3)));

        BizException ex = assertThrows(BizException.class, () -> service.turn(1L, 9L, "你好"));

        assertEquals(ErrorCode.BAD_PARAMS, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("已收起"));
    }

    @Test
    @DisplayName("turn：空白与超长文本都在入口被拒")
    void turnRejectsBlankAndOverlongText() {
        stubActiveSession();

        assertThrows(BizException.class, () -> service.turn(1L, 9L, "   "));
        assertThrows(BizException.class, () -> service.turn(1L, 9L, null));
        assertThrows(BizException.class, () -> service.turn(1L, 9L, "字".repeat(501)));
        verify(llm, never()).stream(any(), any());
    }

    @Test
    @DisplayName("turn：用户级滑动窗超限时给出限流错误")
    void turnEnforcesRateLimit() {
        stubActiveSession();
        when(windowLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(false);

        BizException ex = assertThrows(BizException.class, () -> service.turn(1L, 9L, "在吗"));

        assertEquals(ErrorCode.LLM_RATE_LIMIT, ex.getErrorCode());
    }

    @Test
    @DisplayName("turn：当日达到 200 轮软上限时温柔拒绝并引导写日记")
    void turnEnforcesDailySoftCap() {
        stubActiveSession();
        when(sessionRepo.sumTurnsOnDate(1L, cal.today())).thenReturn(200L);

        BizException ex = assertThrows(BizException.class, () -> service.turn(1L, 9L, "在吗"));

        assertEquals(ErrorCode.BAD_PARAMS, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("200 轮软上限"));
    }

    // ---------------- turn 危机一票 ----------------

    @Test
    @DisplayName("turn：危机命中时当轮替换为陪伴话术、不经 LLM，并同步归档进 S1")
    void turnShortCircuitsOnCrisis() {
        stubActiveSession();
        when(riskEventRepo.save(any(RiskEventEntity.class))).thenAnswer(inv -> {
            RiskEventEntity e = inv.getArgument(0);
            e.setId(5L);
            return e;
        });

        CompanionService.TurnResult r = service.turn(1L, 9L, "我不想活了");

        assertTrue(r.crisis());
        assertEquals(CompanionService.CRISIS_CARE_LINE, r.aiText());
        assertEquals("LOW_ENERGY", r.moodTag());
        verify(llm, never()).stream(any(), any());
        verify(crisisService).onHighRisk(1L, 5L);
        verify(audit).record(eq(1L), eq("RISK_HIGH"), eq("risk_event:5"), eq(null));
    }

    @Test
    @DisplayName("turn：归档环节失败也不吞掉当轮拦截（话术已替换）")
    void turnKeepsCrisisReplyWhenArchivingFails() {
        stubActiveSession();
        when(riskEventRepo.save(any(RiskEventEntity.class))).thenThrow(new IllegalStateException("db down"));

        CompanionService.TurnResult r = service.turn(1L, 9L, "我不想活了");

        assertTrue(r.crisis());
        assertEquals(CompanionService.CRISIS_CARE_LINE, r.aiText());
    }

    @Test
    @DisplayName("turn：否定/引述语境下的降级命中不进强制干预")
    void turnDoesNotEscalateNegatedCrisis() {
        stubActiveSession();
        stubNormalReply("我在听");

        CompanionService.TurnResult r = service.turn(1L, 9L, "朋友说他想过自杀，我很担心");

        assertFalse(r.crisis(), "引述语境降级为 MEDIUM，不触发一票拦截");
    }

    // ---------------- turn 正常路径与降级 ----------------

    @Test
    @DisplayName("turn：正常路径取 LLM 的 reply 字段，并把增量外发给回合流")
    void turnReturnsLlmReplyAndStreamsDeltas() {
        stubActiveSession();
        stubNormalReply("我在听，你慢慢说");
        StringBuilder streamed = new StringBuilder();

        CompanionService.TurnResult r = service.turn(1L, 9L, "今天有点累", streamed::append);

        assertEquals("我在听，你慢慢说", r.aiText());
        assertEquals("LOW_ENERGY", r.moodTag(), "负面措辞走接住策略");
        assertEquals(1, r.turnNo());
        assertEquals(77L, r.turnId());
        assertTrue(streamed.toString().contains("我在听"), "reply 增量应被外发");
    }

    @Test
    @DisplayName("turn：LLM 异常时降级为脚本化回复，不杀会话")
    void turnFallsBackWhenLlmFails() {
        stubActiveSession();
        when(templates.system("companion_v1")).thenReturn("SYS");
        when(templates.render(anyString(), any())).thenReturn("SYS-R");
        when(context.assemble(1L)).thenReturn(new CompanionContextAssembler.ProfileBlock("", null));
        when(llm.stream(any(), any())).thenThrow(new IllegalStateException("llm down"));

        CompanionService.TurnResult r = service.turn(1L, 9L, "今天有点累");

        assertFalse(r.crisis());
        assertEquals("LOW_ENERGY", r.moodTag());
        assertTrue(r.aiText().contains("累"));
        assertTrue(r.aiText().contains("我在听"));
    }

    @Test
    @DisplayName("turn：首轮触发成就评估，失败被吞掉不影响陪伴")
    void turnEvaluatesAchievementOnFirstTurn() {
        stubActiveSession();
        stubNormalReply("在的");
        org.mockito.Mockito.doThrow(new IllegalStateException("achievement down"))
                .when(achievements).evaluate(1L);

        CompanionService.TurnResult r = service.turn(1L, 9L, "你好");

        assertEquals(1, r.turnNo());
        verify(achievements).evaluate(1L);
    }

    @Test
    @DisplayName("turn：非首轮不再重复评估成就")
    void turnSkipsAchievementAfterFirstTurn() {
        when(sessionRepo.findByIdAndUserId(9L, 1L))
                .thenReturn(Optional.of(session(9L, 1L, "ACTIVE", 4)));
        stubNormalReply("在的");

        CompanionService.TurnResult r = service.turn(1L, 9L, "你好");

        assertEquals(5, r.turnNo());
        verify(achievements, never()).evaluate(anyLong());
    }

    @Test
    @DisplayName("turn：剩余轮数按软上限倒计时")
    void turnReportsRemainingToday() {
        stubActiveSession();
        when(sessionRepo.sumTurnsOnDate(1L, cal.today())).thenReturn(10L);
        stubNormalReply("在的");

        assertEquals(189, service.turn(1L, 9L, "你好").remainingToday());
    }

    // ---------------- 防依赖引导 ----------------

    @Test
    @DisplayName("turn：连续三日重度使用后，今日第 101 轮插入一次防依赖引导")
    void turnAddsDependencyGuideOnHeavyStreak() {
        stubActiveSession();
        when(sessionRepo.sumTurnsOnDate(1L, cal.today())).thenReturn(100L);
        when(sessionRepo.sumTurnsByDateSince(eq(1L), any(LocalDate.class))).thenReturn(List.of(
                new Object[]{cal.today(), 150L},
                new Object[]{cal.today().minusDays(1), 130L},
                new Object[]{cal.today().minusDays(2), 120L}));
        stubNormalReply("在的");

        CompanionService.TurnResult r = service.turn(1L, 9L, "你好");

        assertTrue(r.guidanceShown());
        assertTrue(r.aiText().contains(CompanionService.DEPENDENCY_GUIDE));
    }

    @Test
    @DisplayName("引导：仅两天重度或存在一天没过百时不触发")
    void turnSkipsDependencyGuideWhenNotQualified() {
        stubActiveSession();
        when(sessionRepo.sumTurnsOnDate(1L, cal.today())).thenReturn(100L);
        stubNormalReply("在的");

        when(sessionRepo.sumTurnsByDateSince(eq(1L), any(LocalDate.class)))
                .thenReturn(List.of(new Object[]{cal.today(), 150L}, new Object[]{cal.today().minusDays(1), 130L}));
        assertFalse(service.turn(1L, 9L, "你好").guidanceShown(), "不足三天不引导");

        when(sessionRepo.sumTurnsByDateSince(eq(1L), any(LocalDate.class))).thenReturn(List.of(
                new Object[]{cal.today(), 150L},
                new Object[]{cal.today().minusDays(1), 80L},
                new Object[]{cal.today().minusDays(2), 120L}));
        assertFalse(service.turn(1L, 9L, "你好").guidanceShown(), "有一天没过百则不引导");
    }

    @Test
    @DisplayName("引导：只在第 101 轮插一次，其余轮次不打扰")
    void turnOnlyGuidesOnExactTurn() {
        stubActiveSession();
        when(sessionRepo.sumTurnsOnDate(1L, cal.today())).thenReturn(99L);
        stubNormalReply("在的");

        assertFalse(service.turn(1L, 9L, "你好").guidanceShown());
        verify(sessionRepo, never()).sumTurnsByDateSince(anyLong(), any());
    }

    // ---------------- markNoAnalyze / transcript ----------------

    @Test
    @DisplayName("markNoAnalyze：属主标记后该轮被排除出画像管道")
    void markNoAnalyzeFlagsTurn() {
        CompanionTurnEntity t = turn(9L, 1, "这句私事");
        when(turnRepo.findById(107L)).thenReturn(Optional.of(t));
        when(sessionRepo.findByIdAndUserId(9L, 1L)).thenReturn(Optional.of(session(9L, 1L, "ACTIVE", 1)));

        service.markNoAnalyze(1L, 107L);

        assertEquals((short) 1, t.getNoAnalyze());
        verify(turnRepo).save(t);
    }

    @Test
    @DisplayName("markNoAnalyze：轮次不存在或非属主一律 404")
    void markNoAnalyzeRejectsForeignTurn() {
        when(turnRepo.findById(107L)).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.markNoAnalyze(1L, 107L));

        CompanionTurnEntity t = turn(9L, 1, "x");
        when(turnRepo.findById(107L)).thenReturn(Optional.of(t));
        when(sessionRepo.findByIdAndUserId(9L, 1L)).thenReturn(Optional.empty());
        assertThrows(BizException.class, () -> service.markNoAnalyze(1L, 107L));
    }

    @Test
    @DisplayName("transcript：属主回放逐轮，密文解密后给出原文")
    void transcriptDecryptsTurns() throws Exception {
        when(sessionRepo.findByIdAndUserId(9L, 1L))
                .thenReturn(Optional.of(session(9L, 1L, "DIGESTED", 1)));
        CompanionTurnEntity t = turn(9L, 1, "我今天很难过");
        t.setRiskHit((short) 1);
        t.setNoAnalyze((short) 1);
        when(turnRepo.findBySessionIdOrderByTurnNoAsc(9L)).thenReturn(List.of(t));
        when(crypto.decryptUserField(1L, "我今天很难过-enc".getBytes())).thenReturn("我今天很难过");

        JsonNode root = service.transcript(1L, 9L);

        assertEquals(9L, root.path("sessionId").asLong());
        assertEquals(1, root.path("turnList").size());
        JsonNode node = root.path("turnList").get(0);
        assertEquals("我今天很难过", node.path("userText").asText());
        assertTrue(node.path("crisis").asBoolean());
        assertTrue(node.path("noAnalyze").asBoolean());
    }

    @Test
    @DisplayName("transcript：密钥销毁时如实抛错（与 list 侧 safeDecrypt 口径不一致，属已知差异）")
    void transcriptPropagatesWhenKeyDestroyed() {
        when(sessionRepo.findByIdAndUserId(9L, 1L))
                .thenReturn(Optional.of(session(9L, 1L, "DIGESTED", 1)));
        CompanionTurnEntity t = turn(9L, 1, "x");
        when(turnRepo.findBySessionIdOrderByTurnNoAsc(9L)).thenReturn(List.of(t));
        when(crypto.decryptUserField(1L, t.getUserTextEnc()))
                .thenThrow(new IllegalStateException("key destroyed"));

        // 现状：view() 走 safeDecrypt 把解不开的原文降级为空串，transcript() 未做防护。
        // 属主注销后 DEK 已销毁且无法登录，回放接口不可达，故按现状如实钉住，不在补测环节改实现。
        assertThrows(IllegalStateException.class, () -> service.transcript(1L, 9L));
    }

    // ---------------- end / sealAndDigest / sealSilentSessions ----------------

    @Test
    @DisplayName("end：非活跃段返回 null 且不改状态")
    void endReturnsNullForNonActiveSession() {
        when(sessionRepo.findByIdAndUserId(9L, 1L))
                .thenReturn(Optional.of(session(9L, 1L, "DIGESTED", 3)));

        assertNull(service.end(1L, 9L));
        verify(sessionRepo, never()).save(any(CompanionSessionEntity.class));
    }

    @Test
    @DisplayName("end：没有任何可分析内容时只封段、不提交流水线")
    void endSealsWithoutDigestWhenNothingAnalyzable() {
        CompanionSessionEntity s = session(9L, 1L, "ACTIVE", 3);
        when(sessionRepo.findByIdAndUserId(9L, 1L)).thenReturn(Optional.of(s));
        when(turnRepo.findBySessionIdAndNoAnalyzeOrderByTurnNoAsc(9L, (short) 0)).thenReturn(List.of());

        assertNull(service.end(1L, 9L));

        assertEquals("DIGESTED", s.getStatus());
        assertTrue(s.getDigestedAt() != null);
        verify(orchestrator, never()).submit(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("end：可分析轮次存在时提交消化，wantTrace 由负面信号数决定")
    void endSubmitsDigestWithWantTrace() {
        CompanionSessionEntity s = session(9L, 1L, "ACTIVE", 2);
        when(sessionRepo.findByIdAndUserId(9L, 1L)).thenReturn(Optional.of(s));

        CompanionTurnEntity t1 = turn(9L, 1, "我今天很难过");
        CompanionTurnEntity t2 = turn(9L, 2, "我压力好大");
        when(turnRepo.findBySessionIdAndNoAnalyzeOrderByTurnNoAsc(9L, (short) 0)).thenReturn(List.of(t1, t2));
        when(crypto.decryptUserField(1L, t1.getUserTextEnc())).thenReturn("我今天很难过");
        when(crypto.decryptUserField(1L, t2.getUserTextEnc())).thenReturn("我压力好大");
        when(orchestrator.submit(anyLong(), anyString(), any(), anyString())).thenReturn(task("TK-DIG"));

        assertEquals("TK-DIG", service.end(1L, 9L));

        ArgumentCaptor<JsonNode> ic = ArgumentCaptor.forClass(JsonNode.class);
        verify(orchestrator).submit(eq(1L), eq("COMPANION_PIPELINE"), ic.capture(),
                eq("companion-digest-9"));
        assertTrue(ic.getValue().path("wantTrace").asBoolean(), "两条负面信号达到 wantTrace 阈值");
        assertEquals("CHAT", ic.getValue().path("sourceType").asText());
        assertEquals(cal.today().toString(), ic.getValue().path("recordDate").asText());
    }

    @Test
    @DisplayName("end：负面信号不足两条时不请求深度溯源")
    void endSkipsWantTraceOnThinSignal() {
        CompanionSessionEntity s = session(9L, 1L, "ACTIVE", 1);
        when(sessionRepo.findByIdAndUserId(9L, 1L)).thenReturn(Optional.of(s));
        CompanionTurnEntity t1 = turn(9L, 1, "我今天很难过");
        when(turnRepo.findBySessionIdAndNoAnalyzeOrderByTurnNoAsc(9L, (short) 0)).thenReturn(List.of(t1));
        when(crypto.decryptUserField(1L, t1.getUserTextEnc())).thenReturn("我今天很难过");
        when(orchestrator.submit(anyLong(), anyString(), any(), anyString())).thenReturn(task("TK-DIG2"));

        service.end(1L, 9L);

        ArgumentCaptor<JsonNode> ic = ArgumentCaptor.forClass(JsonNode.class);
        verify(orchestrator).submit(anyLong(), anyString(), ic.capture(), anyString());
        assertFalse(ic.getValue().path("wantTrace").asBoolean());
    }

    @Test
    @DisplayName("end：会话级排除标记为 1 时封段但不进画像管道")
    void endSkipsDigestWhenSessionExcluded() {
        CompanionSessionEntity s = session(9L, 1L, "ACTIVE", 3);
        s.setExclFlag((short) 1);
        when(sessionRepo.findByIdAndUserId(9L, 1L)).thenReturn(Optional.of(s));

        assertNull(service.end(1L, 9L));

        assertEquals("DIGESTED", s.getStatus());
        verify(orchestrator, never()).submit(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("end：零轮次的段不提交消化")
    void endSkipsDigestForEmptySession() {
        CompanionSessionEntity s = session(9L, 1L, "ACTIVE", 0);
        when(sessionRepo.findByIdAndUserId(9L, 1L)).thenReturn(Optional.of(s));

        assertNull(service.end(1L, 9L));
        verify(orchestrator, never()).submit(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("sealSilentSessions：逐段封口，单段失败不影响其余")
    void sealSilentSessionsSealsEachAndSwallowsFailure() {
        CompanionSessionEntity ok = session(1L, 7L, "ACTIVE", 0);
        CompanionSessionEntity bad = session(2L, 8L, "ACTIVE", 3);
        when(sessionRepo.findByStatusAndLastTurnAtBefore(eq("ACTIVE"), any(Instant.class)))
                .thenReturn(List.of(ok, bad));
        when(turnRepo.findBySessionIdAndNoAnalyzeOrderByTurnNoAsc(2L, (short) 0))
                .thenThrow(new IllegalStateException("db down"));

        assertEquals(2, service.sealSilentSessions());

        assertEquals("DIGESTED", ok.getStatus());
        assertEquals("ACTIVE", bad.getStatus(), "失败的那段保持原状待下次扫描");
    }
}
