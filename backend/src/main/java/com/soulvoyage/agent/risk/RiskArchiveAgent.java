package com.soulvoyage.agent.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.agent.risk.RiskRules.Level;
import com.soulvoyage.agent.risk.RiskRules.RuleHit;
import com.soulvoyage.audit.AuditService;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.crisis.CrisisService;
import com.soulvoyage.domain.profile.EmotionProfileEntity;
import com.soulvoyage.domain.profile.EmotionProfileRepository;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
import com.soulvoyage.domain.risk.RiskEventEntity;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.simulate.SimulateTurnEntity;
import com.soulvoyage.domain.simulate.SimulateTurnRepository;
import com.soulvoyage.domain.task.AgentMessageEntity;
import com.soulvoyage.domain.task.AgentMessageRepository;
import com.soulvoyage.llm.OutputValidator;
import com.soulvoyage.orchestrator.agent.Agent;
import com.soulvoyage.orchestrator.agent.AgentRuntime;
import com.soulvoyage.orchestrator.protocol.AgentMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 风险研判 & 归档 Agent（手册 §4.5）：全链路唯一出口，每条流水线的强制末步（§3.7）。
 *
 * 双轨研判、取最高级别、规则一票升级：
 *  - 规则轨：{@link RiskRules} 危机词表（日记原文）/ 训练剧情外危机（crisis_flag 轮）→ HIGH；持续低落/自我否定 → MEDIUM。
 *  - 语义轨：上游 Agent 已落在 report.riskLevel 的 LLM 判定（TRACE 的 riskSignals、SIMULATE 复盘）。
 * HIGH → 进入危机生命周期（下篇·S1：CRISIS/RE_ENTRY，由 CrisisService 统一裁决迁移）+ 危机事件加密归档 + 转介；
 * 随后写/更新周画像，产出 ArchiveReceipt。
 * 无 LLM 调用：判定确定性、可回归，符合"高危不依赖模型单独拍板"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskArchiveAgent implements Agent {

    private final CryptoService crypto;
    private final OutputValidator validator;
    private final ReportRepository reportRepo;
    private final AgentMessageRepository msgRepo;
    private final RiskEventRepository riskEventRepo;
    private final EmotionProfileRepository profileRepo;
    private final EmotionTrajectoryRepository trajRepo;
    private final SimulateTurnRepository turnRepo;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final CrisisService crisisService;
    private final BusinessCalendar cal;

    @Override
    public String code() { return "RISK_ARCHIVE"; }

    @Override
    public JsonNode run(AgentMessage request, AgentRuntime rt) {
        JsonNode input = request.payload();
        long userId = rt.userId();
        long taskId = rt.taskId();

        // ---- 规则轨 ----
        RuleTrackResult rule = evalRuleTrack(userId, input);

        // ---- 语义轨（读上游已加密落库的 report.riskLevel）----
        List<ReportEntity> taskReports = reportRepo.findByTaskId(taskId);
        Level semantic = Level.LOW;
        ReportEntity traceReport = null;
        for (ReportEntity r : taskReports) {
            Level lv = parseLevel(r.getRiskLevel());
            semantic = RiskRules.higher(semantic, lv);
            if ("TRACE".equals(r.getType()) && traceReport == null) traceReport = r;
        }

        Level finalLevel = RiskRules.higher(rule.level, semantic);
        String triggerType = finalLevel == Level.LOW ? null
                : (rule.level.ordinal() >= semantic.ordinal() ? rule.triggerType : "LLM_SEMANTIC");
        String ruleCode = rule.level.ordinal() >= semantic.ordinal() ? rule.ruleCode : null;

        Long riskEventId = null;
        boolean showReferral = finalLevel != Level.LOW;
        if (finalLevel != Level.LOW) {
            riskEventId = archiveRiskEvent(userId, taskId, finalLevel, triggerType, ruleCode,
                    rule.locator, rule.matchedTurn, rule.needsReview());
        }
        if (finalLevel == Level.HIGH) {
            crisisService.onHighRisk(userId, riskEventId);
        }

        boolean profileUpdated = updateWeeklyProfile(userId, cal.today(), finalLevel);

        ObjectNode receipt = buildReceipt(finalLevel, riskEventId, showReferral, taskId,
                taskReports.size(), userId, profileUpdated);
        return validator.validate(rt.spec().outputSchema(), receipt.toString());
    }

    // ---------------- 规则轨 ----------------

    private record RuleTrackResult(Level level, String triggerType, String ruleCode,
                                   String locator, Long matchedTurn, boolean needsReview) {}

    private RuleTrackResult evalRuleTrack(long userId, JsonNode input) {
        // 训练：剧情外真实危机（会话轮 crisis_flag 已由 NpcDirector 借同一危机词表裁决）
        long simulateId = input.path("simulateId").asLong(0);
        if (simulateId > 0) {
            for (SimulateTurnEntity t : turnRepo.findBySimulateIdOrderByTurnNoAsc(simulateId)) {
                if (t.getCrisisFlag() == 1) {
                    return new RuleTrackResult(Level.HIGH, "SIMULATE_BREAKOUT", "RISK_CRISIS",
                            "sim=" + simulateId + " turn=" + t.getTurnNo(), t.getId(), false);
                }
            }
            return new RuleTrackResult(Level.LOW, null, null, null, null, false);
        }
        // 日记/漫聊消化：文本规则扫描（CHAT 场景 HIGH 已在逐轮一票时同步归档，消化阶段不重复计）
        String text = input.path("diaryText").asText("");
        List<RuleHit> hits = RiskRules.scan(text);
        Level lv = RiskRules.maxLevel(hits);
        if (lv == Level.LOW) return new RuleTrackResult(Level.LOW, null, null, null, null, false);
        RuleHit top = hits.get(0);
        for (RuleHit h : hits) if (h.level() == Level.HIGH) { top = h; break; }
        if (top.level() == Level.HIGH && input.path("turnRiskHandled").asBoolean(false)) {
            return new RuleTrackResult(Level.LOW, null, null, null, null, false);
        }
        return new RuleTrackResult(top.level(), top.triggerType(), top.ruleCode(), top.locator(),
                null, top.needsReview());
    }

    // ---------------- 归档 / 危机动作 ----------------

    private Long archiveRiskEvent(long userId, long taskId, Level level, String triggerType,
                                  String ruleCode, String locator, Long turnId, boolean needsReview) {
        ObjectNode evidence = mapper.createObjectNode();
        evidence.put("ruleCode", ruleCode);
        evidence.put("locator", locator);
        if (turnId != null) evidence.put("turnId", turnId);
        evidence.put("level", level.name());

        RiskEventEntity e = new RiskEventEntity();
        e.setUserId(userId);
        e.setLevel(level.name());
        e.setTriggerType(triggerType);
        e.setRuleCode(ruleCode);
        e.setEvidenceRefEnc(crypto.encryptUserField(userId, evidence.toString()));
        e.setActionTaken(level == Level.HIGH ? "CRISIS_CARD+PROFILE_FLAG" : "REFERRAL_UPGRADE");
        e.setTaskId(taskId);
        e.setNeedsReview((short) (needsReview ? 1 : 0));
        e = riskEventRepo.save(e);

        audit.record(userId, level == Level.HIGH ? "RISK_HIGH" : "RISK_MEDIUM",
                "risk_event:" + e.getId(), null);
        return e.getId();
    }

    // ---------------- 周画像 upsert ----------------

    private boolean updateWeeklyProfile(long userId, LocalDate date, Level finalLevel) {
        try {
            WeekFields wf = WeekFields.ISO;
            LocalDate weekStart = date.with(wf.dayOfWeek(), 1);
            LocalDate weekEnd = weekStart.plusDays(6);
            String statWeek = date.get(wf.weekBasedYear()) + "-W"
                    + String.format("%02d", date.get(wf.weekOfWeekBasedYear()));

            List<EmotionTrajectoryEntity> traj = trajRepo
                    .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(userId, weekStart, weekEnd);

            BigDecimal avg = null;
            if (!traj.isEmpty()) {
                BigDecimal sum = BigDecimal.ZERO;
                for (EmotionTrajectoryEntity t : traj) sum = sum.add(t.getValence());
                avg = sum.divide(BigDecimal.valueOf(traj.size()), 3, RoundingMode.HALF_UP);
            }

            List<ReportEntity> traceReports = reportRepo
                    .findByUserIdAndTypeAndDeletedAtIsNull(userId, "TRACE");
            Map<String, Integer> stressors = new HashMap<>();
            Map<String, Integer> distortions = new HashMap<>();
            Instant wStart = weekStart.atStartOfDay(ZoneOffset.UTC).toInstant();
            for (ReportEntity r : traceReports) {
                // 生产 MySQL 由 DEFAULT CURRENT_TIMESTAMP 填充；H2 测试库无默认值时视为当周新报告
                if (r.getCreatedAt() != null && r.getCreatedAt().isBefore(wStart)) continue;
                aggregateTrace(userId, r, stressors, distortions);
            }

            EmotionProfileEntity p = profileRepo.findByUserIdAndStatWeek(userId, statWeek)
                    .orElseGet(() -> {
                        EmotionProfileEntity n = new EmotionProfileEntity();
                        n.setUserId(userId);
                        n.setStatWeek(statWeek);
                        return n;
                    });
            p.setAvgValence(avg);
            p.setStressorTopJson(topJson(stressors));
            p.setDistortionTopJson(topJson(distortions));
            Level before = parseLevel(p.getRiskLevel());
            p.setRiskLevel(RiskRules.higher(before, finalLevel).name());
            profileRepo.save(p);
            return true;
        } catch (Exception e) {
            // 归档失败 > 用户体验：画像更新失败不阻断，receipt.profileUpdated=false
            log.error("weekly profile update failed for user {}", userId, e);
            return false;
        }
    }

    private void aggregateTrace(long userId, ReportEntity r,
                                Map<String, Integer> stressors, Map<String, Integer> distortions) {
        try {
            JsonNode content = mapper.readTree(crypto.decryptUserField(userId, r.getContentEnc()));
            for (JsonNode s : content.path("stressors")) {
                String k = s.path("source").asText("");
                if (!k.isBlank()) stressors.merge(k, 1, Integer::sum);
            }
            for (JsonNode d : content.path("cognitiveDistortions")) {
                String k = d.path("name").asText("");
                if (!k.isBlank()) distortions.merge(k, 1, Integer::sum);
            }
        } catch (Exception ignored) {
            // 解密失败（历史密钥等）不阻断画像统计
        }
    }

    private String topJson(Map<String, Integer> counts) {
        var arr = mapper.createArrayNode();
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(3)
                .forEach(e -> arr.addObject().put("name", e.getKey()).put("count", e.getValue()));
        return arr.toString();
    }

    // ---------------- 回执 ----------------

    private ObjectNode buildReceipt(Level level, Long riskEventId, boolean showReferral,
                                    long taskId, int reportCount, long userId, boolean profileUpdated) {
        List<AgentMessageEntity> msgs = msgRepo.findByTaskIdOrderByStepSeqAscIdAsc(taskId);
        long emotionPoints = msgs.stream()
                .filter(m -> "EMOTION".equals(m.getFromAgent()) && "MIDDLE_RESULT".equals(m.getMsgType()))
                .count();

        var out = mapper.createObjectNode();
        out.put("riskLevel", level.name());
        if (riskEventId == null) out.putNull("riskEventId"); else out.put("riskEventId", riskEventId);
        if (showReferral) {
            ObjectNode ref = out.putObject("referral");
            ref.put("show", true);
            ref.put("level", level.name());
            ref.put("headline", level == Level.HIGH
                    ? "现在这一刻也许很难，你的安全最重要——请连接下面的支持" : "这里有一些可能帮到你的资源");
        } else {
            out.putNull("referral");
        }
        ObjectNode archived = out.putObject("archived");
        archived.put("emotionPoints", (int) emotionPoints);
        archived.put("reports", reportCount);
        archived.put("messages", msgs.size());
        ObjectNode enc = out.putObject("encryption");
        enc.put("algo", "AES-256-GCM");
        enc.put("keyVersion", currentKeyVersion(userId));
        out.put("profileUpdated", profileUpdated);
        return out;
    }

    private String currentKeyVersion(long userId) {
        try {
            byte[] probe = crypto.encryptUserField(userId, "k");
            return "k" + (probe[0] & 0xFF);
        } catch (Exception e) {
            return "k0";
        }
    }

    private static Level parseLevel(String s) {
        if (s == null) return Level.LOW;
        try {
            return Level.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return Level.LOW;
        }
    }
}
