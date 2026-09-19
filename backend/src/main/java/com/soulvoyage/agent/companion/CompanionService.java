package com.soulvoyage.agent.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.agent.risk.RiskRules;
import com.soulvoyage.audit.AuditService;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.companion.CompanionSessionEntity;
import com.soulvoyage.domain.companion.CompanionSessionRepository;
import com.soulvoyage.domain.companion.CompanionTurnEntity;
import com.soulvoyage.domain.companion.CompanionTurnRepository;
import com.soulvoyage.domain.crisis.CrisisService;
import com.soulvoyage.domain.notify.UserPreferencesRepository;
import com.soulvoyage.domain.risk.RiskEventEntity;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.llm.LlmClient;
import com.soulvoyage.llm.OutputValidator;
import com.soulvoyage.llm.PromptTemplates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 树洞漫聊会话引擎（下篇·C0，手册 §4.6）：复用模拟训练的"逐轮无状态请求 + SSE"形态，
 * 但去掉张力状态机——导演模块换成 {@link CompanionMood} 情绪响应策略。
 * 三条硬护栏：①每轮入口同步 RiskRules 一票（HIGH 当轮拦截进 S1，P0 中 P0）；
 * ②软上限 200 轮/日 + 用户级滑动窗限流（成本）；③连续 3 日 >100 轮/日 → 一次防依赖引导（陪伴导向行动）。
 * 会话按"自然日 + 30min 静默"分段；封段后交 COMPANION_PIPELINE 异步消化（可被「这句别分析」/总开关排除）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanionService {

    /** 漫聊逐轮危机拦截话术：不跳出"岛民"人设也要把人接住（SOP §7.3 第②步同款语气） */
    public static final String CRISIS_CARE_LINE =
            "等一下——你刚才这句话，我有点担心你。先别聊别的，此刻你安全吗？"
                    + "如果很难熬，请联系信任的人，或随时拨打心理援助热线 12356。我会一直在这里。";

    /** 防依赖引导（C0 边界守护）：陪伴导向行动，不导向依赖 */
    public static final String DEPENDENCY_GUIDE =
            "这几天我们聊得很多，我挺珍视这些时刻的。要不要试着把心事写成一篇日记做个深度梳理？"
                    + "或者如果压力实在很重，校心理中心也是一个可以靠一靠的地方。";

    static final int SOFT_CAP_TURNS_PER_DAY = 200;      // 成本护栏（软上限）
    static final int GUIDE_OVER_DAILY_TURNS = 100;      // 防依赖：单日 >100 轮视为"重度"
    private static final int GUIDE_CONSECUTIVE_DAYS = 3;
    private static final Duration SILENCE_WINDOW = Duration.ofMinutes(30);
    private static final int TRANSCRIPT_WINDOW = 8;
    private static final int RATE_LIMIT_PER_MIN = 20;

    private final CompanionSessionRepository sessionRepo;
    private final CompanionTurnRepository turnRepo;
    private final UserPreferencesRepository prefsRepo;
    private final RiskEventRepository riskEventRepo;
    private final CryptoService crypto;
    private final LlmClient llm;
    private final PromptTemplates templates;
    private final OutputValidator validator;
    private final ObjectMapper mapper;
    private final BusinessCalendar cal;
    private final com.soulvoyage.orchestrator.OrchestratorService orchestrator;
    private final CrisisService crisisService;
    private final AuditService audit;
    private final CompanionContextAssembler context;
    private final com.soulvoyage.domain.achievement.AchievementService achievements;

    /** 用户级滑动窗限流（单节点内存版；多节点可行性归 Redis 化技术债） */
    private final Map<Long, ArrayDeque<Instant>> rateWindow = new ConcurrentHashMap<>();

    public record TurnView(long turnId, int turnNo, String userText, String aiText, String moodTag,
                           boolean crisis, boolean noAnalyze, Instant createdAt) {}

    public record SessionView(long sessionId, LocalDate chatDate, int segmentNo, String status,
                              int turns, List<TurnView> turnList) {}

    public record TurnResult(long turnId, int turnNo, String aiText, String moodTag, boolean crisis,
                             boolean guidanceShown, int remainingToday) {}

    // ---------------- 会话生命周期 ----------------

    /** 打开/续聊：今日 ACTIVE 且在静默窗内直接续；否则封旧段开新段（无 LLM 消耗） */
    public SessionView openOrReuse(long userId) {
        LocalDate today = cal.today();
        List<CompanionSessionEntity> todays =
                sessionRepo.findByUserIdAndChatDateOrderBySegmentNoDesc(userId, today);
        CompanionSessionEntity active = todays.stream()
                .filter(s -> "ACTIVE".equals(s.getStatus())).findFirst().orElse(null);
        if (active != null && active.getTurns() > 0 && isSilent(active)) {
            sealAndDigest(active);                       // 静默超窗：封口消化，另起新段
            active = null;
        }
        if (active == null) {
            active = new CompanionSessionEntity();
            active.setUserId(userId);
            active.setChatDate(today);
            active.setSegmentNo(todays.isEmpty() ? 1 : todays.get(0).getSegmentNo() + 1);
            active.setLastTurnAt(Instant.now());
            boolean analysisOff = prefsRepo.findByUserId(userId)
                    .map(p -> p.getCompanionAnalysisOn() == 0).orElse(false);
            active.setExclFlag((short) (analysisOff ? 1 : 0));
            active = sessionRepo.save(active);
        }
        return view(active, false);
    }

    /** 今日活跃会话（续聊胶囊数据源）；无则返回 null */
    public SessionView activeView(long userId) {
        LocalDate today = cal.today();
        return sessionRepo.findByUserIdAndChatDateAndStatus(userId, today, "ACTIVE")
                .map(s -> view(s, true)).orElse(null);
    }

    public Page<CompanionSessionEntity> history(long userId, Pageable pageable) {
        return sessionRepo.findByUserIdOrderByChatDateDescIdDesc(userId, pageable);
    }

    /** 逐轮：危机一票 → 响应策略 → LLM 措辞（校验失败降级模板句，不杀会话） */
    public TurnResult turn(long userId, long sessionId, String userText) {
        CompanionSessionEntity s = owned(userId, sessionId);
        if (!"ACTIVE".equals(s.getStatus())) {
            throw new BizException(ErrorCode.BAD_PARAMS, "该段对话已收起，继续聊请开新段");
        }
        if (userText == null || userText.isBlank()) {
            throw new BizException(ErrorCode.BAD_PARAMS, "想说的话不能为空");
        }
        if (userText.length() > 500) {
            throw new BizException(ErrorCode.BAD_PARAMS, "单条消息过长（≤500字）");
        }
        rateLimit(userId);
        long turnsToday = sessionRepo.sumTurnsOnDate(userId, s.getChatDate());
        if (turnsToday >= SOFT_CAP_TURNS_PER_DAY) {
            throw new BizException(ErrorCode.BAD_PARAMS,
                    "今天已经聊了很久啦（200 轮软上限），明天我还在——要不要把今天最想说的一句写进日记？");
        }

        int turnNo = s.getTurns() + 1;
        boolean crisis = RiskRules.isCrisis(userText);   // 回合入口同步执行，规则一票（P0 中 P0）
        String aiText;
        String moodTag;
        if (crisis) {
            aiText = CRISIS_CARE_LINE;                   // 当轮回复替换为温和陪伴，不经 LLM
            moodTag = "LOW_ENERGY";
            archiveTurnRisk(userId, s, turnNo, userText);
        } else {
            CompanionMood.Strategy strategy = CompanionMood.decide(userText, recentMoodTags(s.getId()));
            try {
                aiText = companionLlmReply(s, strategy, turnNo, userText);
            } catch (Exception e) {
                log.warn("companion reply degraded at session={} turn={}: {}", sessionId, turnNo, e.getMessage());
                aiText = scriptedFallback(strategy, userText);
            }
            moodTag = strategy.name();
        }

        boolean guidance = maybeDependencyGuide(userId, s, turnsToday, moodTag);
        if (guidance) aiText = aiText + "\n\n" + DEPENDENCY_GUIDE;

        long turnId = persistTurn(userId, s, turnNo, userText, aiText, moodTag, crisis);
        if (turnNo == 1) {
            try {
                achievements.evaluate(userId);   // COMPANION_OPENED 成就；失败不阻断陪伴
            } catch (Exception e) {
                log.warn("achievement eval after first companion turn failed user={}", userId, e);
            }
        }
        return new TurnResult(turnId, turnNo, aiText, moodTag, crisis, guidance,
                (int) (SOFT_CAP_TURNS_PER_DAY - turnsToday - 1));
    }

    /** 用户自主权：「这句别分析」→ 下一段消化时排除 */
    public void markNoAnalyze(long userId, long turnId) {
        CompanionTurnEntity t = turnRepo.findById(turnId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
        owned(userId, t.getSessionId());
        t.setNoAnalyze((short) 1);
        turnRepo.save(t);
    }

    /** 手动收段：封口 + 交 COMPANION_PIPELINE，返回 taskNo（无可分析内容或开关关闭时为 null） */
    public String end(long userId, long sessionId) {
        CompanionSessionEntity s = owned(userId, sessionId);
        if ("ACTIVE".equals(s.getStatus())) return sealAndDigest(s);
        return null;
    }

    /** 回放（属主解密），同 simulate 规范 */
    public JsonNode transcript(long userId, long sessionId) throws Exception {
        CompanionSessionEntity s = owned(userId, sessionId);
        ObjectNode root = mapper.createObjectNode();
        root.put("sessionId", s.getId());
        root.put("chatDate", s.getChatDate().toString());
        root.put("segmentNo", s.getSegmentNo());
        root.put("status", s.getStatus());
        root.put("turns", s.getTurns());
        ArrayNode arr = root.putArray("turnList");
        for (CompanionTurnEntity t : turnRepo.findBySessionIdOrderByTurnNoAsc(sessionId)) {
            ObjectNode n = arr.addObject();
            n.put("turnId", t.getId());
            n.put("turnNo", t.getTurnNo());
            n.put("userText", crypto.decryptUserField(userId, t.getUserTextEnc()));
            n.put("aiText", t.getAiText());
            n.put("moodTag", t.getMoodTag());
            n.put("crisis", t.getRiskHit() == 1);
            n.put("noAnalyze", t.getNoAnalyze() == 1);
        }
        return root;
    }

    // ---------------- 封口 / 消化 ----------------

    /** 静默 30min 封段扫描（定时任务与打开新段共用入口；测试直调） */
    public int sealSilentSessions() {
        Instant cutoff = Instant.now().minus(SILENCE_WINDOW);
        List<CompanionSessionEntity> silent = sessionRepo.findByStatusAndLastTurnAtBefore("ACTIVE", cutoff);
        for (CompanionSessionEntity s : silent) {
            try {
                sealAndDigest(s);
            } catch (Exception e) {
                log.warn("seal companion session {} failed: {}", s.getId(), e.toString());
            }
        }
        return silent.size();
    }

    /**
     * 封口并消化：总开关关闭或全部标了「这句别分析」→ 仅封段不进画像管道。
     * wantTrace 判据在封段时确定性算好（流水线步骤只在启动时求值一次）。
     */
    private String sealAndDigest(CompanionSessionEntity s) {
        String taskNo = null;
        if (s.getTurns() > 0 && s.getExclFlag() == 0) {
            List<CompanionTurnEntity> analyzed =
                    turnRepo.findBySessionIdAndNoAnalyzeOrderByTurnNoAsc(s.getId(), (short) 0);
            int negative = 0;
            for (CompanionTurnEntity t : analyzed) {
                String text = safeDecrypt(s.getUserId(), t.getUserTextEnc());
                if (CompanionMood.probe(text) < 0) negative++;
                for (RiskRules.RuleHit h : RiskRules.scan(text))
                    if (h.level() != RiskRules.Level.LOW) negative++;
            }
            if (!analyzed.isEmpty()) {
                ObjectNode input = mapper.createObjectNode()
                        .put("sessionId", s.getId())
                        .put("sourceType", "CHAT")
                        .put("recordDate", s.getChatDate().toString())
                        .put("wantTrace", negative >= 2)
                        .put("turnRiskHandled", true);   // HIGH 已逐轮同步归档，消化阶段规则轨只留 MEDIUM 语义
                taskNo = orchestrator.submit(s.getUserId(), "COMPANION_PIPELINE", input,
                        "companion-digest-" + s.getId()).getTaskNo();
            }
        }
        s.setStatus("DIGESTED");
        s.setDigestedAt(Instant.now());
        sessionRepo.save(s);
        log.info("companion session {} sealed (turns={}, digestTask={})", s.getId(), s.getTurns(), taskNo);
        return taskNo;
    }

    // ---------------- 内部 ----------------

    private CompanionSessionEntity owned(long userId, long sessionId) {
        return sessionRepo.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
    }

    private boolean isSilent(CompanionSessionEntity s) {
        return s.getLastTurnAt() == null || s.getLastTurnAt().isBefore(Instant.now().minus(SILENCE_WINDOW));
    }

    private SessionView view(CompanionSessionEntity s, boolean withTurns) {
        java.util.List<TurnView> turns = new java.util.ArrayList<>();
        if (withTurns) {
            for (CompanionTurnEntity t : turnRepo.findBySessionIdOrderByTurnNoAsc(s.getId())) {
                turns.add(new TurnView(t.getId(), t.getTurnNo(), safeDecrypt(s.getUserId(), t.getUserTextEnc()),
                        t.getAiText(), t.getMoodTag(), t.getRiskHit() == 1, t.getNoAnalyze() == 1,
                        t.getCreatedAt()));
            }
        }
        return new SessionView(s.getId(), s.getChatDate(), s.getSegmentNo(), s.getStatus(), s.getTurns(), turns);
    }

    private String safeDecrypt(long userId, byte[] blob) {
        try {
            return crypto.decryptUserField(userId, blob);
        } catch (Exception e) {
            return "";   // 密钥销毁（注销即遗忘）等场景：空串即"无信号"，不炸管道
        }
    }

    private void rateLimit(long userId) {
        Instant now = Instant.now();
        ArrayDeque<Instant> q = rateWindow.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minusSeconds(60))) q.pollFirst();
            if (q.size() >= RATE_LIMIT_PER_MIN) {
                throw new BizException(ErrorCode.LLM_RATE_LIMIT, "消息发得太快啦，缓一缓再继续聊");
            }
            q.addLast(now);
        }
    }

    /** HIGH 一票：当轮同步归档风险事件并进入 S1 危机生命周期（不等异步消化——倾诉场景最高发入口） */
    private void archiveTurnRisk(long userId, CompanionSessionEntity s, int turnNo, String userText) {
        try {
            ObjectNode evidence = mapper.createObjectNode();
            evidence.put("ruleCode", "RISK_CRISIS");
            evidence.put("locator", "companion=" + s.getId() + " turn=" + turnNo);
            evidence.put("level", "HIGH");
            RiskEventEntity e = new RiskEventEntity();
            e.setUserId(userId);
            e.setLevel("HIGH");
            e.setTriggerType("COMPANION_TURN");
            e.setRuleCode("RISK_CRISIS");
            e.setEvidenceRefEnc(crypto.encryptUserField(userId, evidence.toString()));
            e.setActionTaken("CRISIS_CARD+PROFILE_FLAG");
            e.setNeedsReview((short) 0);
            e = riskEventRepo.save(e);
            crisisService.onHighRisk(userId, e.getId());
            audit.record(userId, "RISK_HIGH", "risk_event:" + e.getId(), null);
        } catch (Exception ex) {
            // 归档失败不能吞掉当轮拦截：话术替换已生效，仅记日志供人工补录
            log.error("companion crisis archiving failed user={} session={}", userId, s.getId(), ex);
        }
    }

    /** 防依赖引导：连续 3 日（含今日）均 >100 轮，在今日第 101 轮插一次 */
    private boolean maybeDependencyGuide(long userId, CompanionSessionEntity s, long turnsToday, String moodTag) {
        if (turnsToday + 1 != GUIDE_OVER_DAILY_TURNS + 1) return false;
        List<Object[]> sums = sessionRepo.sumTurnsByDateSince(userId, s.getChatDate().minusDays(GUIDE_CONSECUTIVE_DAYS - 1L));
        if (sums.size() < GUIDE_CONSECUTIVE_DAYS) return false;
        for (Object[] row : sums) {
            if (((Number) row[1]).longValue() <= GUIDE_OVER_DAILY_TURNS) return false;
        }
        return true;
    }

    private List<String> recentMoodTags(long sessionId) {
        List<CompanionTurnEntity> all = turnRepo.findBySessionIdOrderByTurnNoAsc(sessionId);
        return all.stream().map(CompanionTurnEntity::getMoodTag).filter(java.util.Objects::nonNull).toList();
    }

    private String companionLlmReply(CompanionSessionEntity s, CompanionMood.Strategy strategy,
                                     int turnNo, String userText) throws Exception {
        CompanionContextAssembler.ProfileBlock p = context.assemble(s.getUserId());
        String system = templates.render(templates.system("companion_v1"), Map.of(
                "moodDirective", CompanionMood.directive(strategy),
                "profileBlock", p.summary().isBlank() ? "（还没有足够记录，先单纯认识 TA）" : p.summary()));
        ObjectNode user = mapper.createObjectNode();
        user.put("sessionId", s.getId());
        user.put("turnNo", turnNo);
        user.put("mood", strategy.name());
        user.put("profileFollowUp", turnNo == 1 && p.topEventTag() != null);
        user.put("profileEntity", p.topEventTag() == null ? "" : p.topEventTag());
        ArrayNode tr = user.putArray("transcript");
        List<CompanionTurnEntity> history = turnRepo.findBySessionIdOrderByTurnNoAsc(s.getId());
        int from = Math.max(0, history.size() - TRANSCRIPT_WINDOW);
        for (CompanionTurnEntity t : history.subList(from, history.size())) {
            tr.addObject()
                    .put("turn", t.getTurnNo())
                    .put("user", safeDecrypt(s.getUserId(), t.getUserTextEnc()))
                    .put("ai", t.getAiText());
        }
        tr.addObject().put("turn", turnNo).put("user", userText);
        var resp = llm.chat(new LlmClient.LlmRequest("companion_v1", system, user.toString(), 300));
        JsonNode out = validator.validate("companion_reply.json", resp.content());
        return out.path("reply").asText();
    }

    private String scriptedFallback(CompanionMood.Strategy strategy, String userText) {
        String echo = userText.length() > 20 ? userText.substring(0, 20) + "…" : userText;
        return switch (strategy) {
            case LOW_ENERGY -> "嗯，我在听。「" + echo + "」——这种事搁谁身上都不轻松。";
            case WARM_UP -> "感觉你这边好像松了一点？「" + echo + "」挺好的，多和我说说。";
            case FOLLOW -> "「" + echo + "」，然后呢？我想听。";
        };
    }

    private long persistTurn(long userId, CompanionSessionEntity s, int turnNo,
                             String userText, String aiText, String moodTag, boolean crisis) {
        CompanionTurnEntity t = new CompanionTurnEntity();
        t.setSessionId(s.getId());
        t.setTurnNo(turnNo);
        t.setUserTextEnc(crypto.encryptUserField(userId, userText));
        t.setAiText(aiText.length() > 2000 ? aiText.substring(0, 2000) : aiText);
        t.setMoodTag(moodTag);
        t.setRiskHit((short) (crisis ? 1 : 0));
        turnRepo.save(t);
        s.setTurns(turnNo);
        s.setLastTurnAt(Instant.now());
        sessionRepo.save(s);
        return t.getId();
    }
}
