package com.leyon.soul.agent.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leyon.soul.agent.risk.RiskRules.Level;
import com.leyon.soul.agent.risk.RiskRules.RuleHit;
import com.leyon.soul.common.time.BusinessCalendar;
import com.leyon.soul.crypto.CryptoService;
import com.leyon.soul.domain.crisis.CrisisService;
import com.leyon.soul.domain.report.ReportEntity;
import com.leyon.soul.domain.report.ReportRepository;
import com.leyon.soul.domain.simulate.SimulateTurnEntity;
import com.leyon.soul.domain.simulate.SimulateTurnRepository;
import com.leyon.soul.domain.task.AgentMessageEntity;
import com.leyon.soul.domain.task.AgentMessageRepository;
import com.leyon.soul.llm.OutputValidator;
import com.leyon.soul.orchestrator.AgentCode;
import com.leyon.soul.orchestrator.agent.Agent;
import com.leyon.soul.orchestrator.agent.AgentRuntime;
import com.leyon.soul.orchestrator.protocol.AgentMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

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
    private final SimulateTurnRepository turnRepo;
    private final RiskArchiveService riskArchive;
    private final ObjectMapper mapper;
    private final CrisisService crisisService;
    private final BusinessCalendar cal;

    @Override
    public AgentCode code() { return AgentCode.RISK_ARCHIVE; }

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
            Level lv = RiskArchiveService.parseLevel(r.getRiskLevel());
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
            // 持久化下沉到 RiskArchiveService：事件 + 审计 + 指标同一事务单元（B-13）
            riskEventId = riskArchive.record(userId, taskId, finalLevel, triggerType, ruleCode,
                    rule.locator, rule.matchedTurn, rule.needsReview());
        }
        if (finalLevel == Level.HIGH) {
            crisisService.onHighRisk(userId, riskEventId);
        }

        boolean profileUpdated = riskArchive.updateWeeklyProfile(userId, cal.today(), finalLevel);

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
        return "k" + crypto.activeKeyVersion(userId);
    }
}
