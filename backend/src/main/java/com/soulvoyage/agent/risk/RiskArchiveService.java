package com.soulvoyage.agent.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.agent.risk.RiskRules.Level;
import com.soulvoyage.audit.AuditService;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.profile.EmotionProfileEntity;
import com.soulvoyage.domain.profile.EmotionProfileRepository;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
import com.soulvoyage.domain.risk.RiskEventEntity;
import com.soulvoyage.domain.risk.RiskEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 风险归档持久化（B-13）：把风险事件、审计、指标从判定 Agent 中下沉，明确事务边界。
 *  - {@link #record}：风险事件 + 审计 + sv.risk.event 计数，同一事务单元；审计沿用自身 REQUIRES_NEW 语义（C-05 零改动）。
 *  - {@link #updateWeeklyProfile}：独立事务（REQUIRES_NEW），失败只回滚画像并返回 false，不阻断归档回执。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskArchiveService {

    private final CryptoService crypto;
    private final RiskEventRepository riskEventRepo;
    private final EmotionProfileRepository profileRepo;
    private final EmotionTrajectoryRepository trajRepo;
    private final ReportRepository reportRepo;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;
    private final PlatformTransactionManager txManager;

    /** 风险事件 + 审计 + 指标（原子单元） */
    @Transactional
    public Long record(long userId, long taskId, Level level, String triggerType,
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
        registry.counter("sv.risk.event", "level", level.name()).increment();

        audit.record(userId, level == Level.HIGH ? "RISK_HIGH" : "RISK_MEDIUM",
                "risk_event:" + e.getId(), null);
        return e.getId();
    }

    /** 周画像 upsert：独立事务，失败不阻断（profileUpdated=false） */
    public boolean updateWeeklyProfile(long userId, LocalDate date, Level finalLevel) {
        try {
            return Boolean.TRUE.equals(requiresNew().execute(status -> {
                updateWeeklyProfileInTx(userId, date, finalLevel);
                return true;
            }));
        } catch (Exception e) {
            // 归档失败 > 用户体验：画像更新失败不阻断，receipt.profileUpdated=false
            log.error("weekly profile update failed for user {}", userId, e);
            return false;
        }
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    private void updateWeeklyProfileInTx(long userId, LocalDate date, Level finalLevel) {
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

    static Level parseLevel(String s) {
        if (s == null) return Level.LOW;
        try {
            return Level.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return Level.LOW;
        }
    }
}
