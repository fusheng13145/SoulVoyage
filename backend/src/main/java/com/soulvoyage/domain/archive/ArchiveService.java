package com.soulvoyage.domain.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.audit.AuditService;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.util.Ulid;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.exercise.ExerciseRecordEntity;
import com.soulvoyage.domain.exercise.ExerciseRecordRepository;
import com.soulvoyage.domain.profile.EmotionProfileEntity;
import com.soulvoyage.domain.profile.EmotionProfileRepository;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
import com.soulvoyage.domain.risk.RiskEventEntity;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 成长档案与限时导出（手册 §4.5 / §6.5 / §7.2）。
 * 导出兜底方案按手册风险预案降级：不落 PDF 库，生成加密报告的明文快照放**内存 TTL**，
 * 链接限时一次性（下载即焚 + 过期自毁），前端以打印样式页呈现。审计记录 EXPORT/EXPORT_DOWNLOAD。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArchiveService {

    private static final Duration EXPORT_TTL = Duration.ofMinutes(5);

    private final ReportRepository reportRepo;
    private final EmotionTrajectoryRepository trajRepo;
    private final EmotionProfileRepository profileRepo;
    private final RiskEventRepository riskEventRepo;
    private final ExerciseRecordRepository exerciseRepo;
    private final UserRepository userRepo;
    private final CryptoService crypto;
    private final AuditService audit;
    private final ObjectMapper mapper;

    private record Snapshot(long userId, ObjectNode data, Instant expiresAt) {}

    private final Map<String, Snapshot> exports = new ConcurrentHashMap<>();

    /** 周报（手册 §4.5 成长档案：情绪曲线 + 压力源 Top + 误区趋势 + 训练分趋势） */
    public ObjectNode weeklySummary(long userId, LocalDate date) {
        LocalDate weekStart = date.with(java.time.temporal.WeekFields.ISO.dayOfWeek(), 1);
        String statWeek = date.get(java.time.temporal.WeekFields.ISO.weekBasedYear()) + "-W"
                + String.format("%02d", date.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()));

        var root = mapper.createObjectNode();
        root.put("statWeek", statWeek);
        root.put("weekStart", weekStart.toString());

        EmotionProfileEntity p = profileRepo.findByUserIdAndStatWeek(userId, statWeek).orElse(null);
        ObjectNode profile = root.putObject("profile");
        if (p != null) {
            profile.put("avgValence", p.getAvgValence() == null ? null : p.getAvgValence().toString());
            profile.put("riskLevel", p.getRiskLevel());
            readInto(profile, "stressorTop", p.getStressorTopJson());
            readInto(profile, "distortionTop", p.getDistortionTopJson());
        } else {
            profile.put("riskLevel", "LOW");
        }

        ArrayNode points = root.putArray("emotionPoints");
        for (EmotionTrajectoryEntity t : trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
                userId, weekStart, weekStart.plusDays(6))) {
            ObjectNode n = points.addObject();
            n.put("date", t.getRecordDate().toString());
            n.put("emotion", t.getPrimaryEmotion());
            n.put("valence", t.getValence().toString());
            n.put("intensity", t.getIntensity().toString());
            n.put("sourceType", t.getSourceType());
        }

        root.set("reports", filterReportsInRange(userId, weekStart, weekStart.plusDays(6)));
        ArrayNode trainings = root.putArray("trainingScores");
        for (ReportEntity r : reportRepo.findByUserIdAndTypeAndDeletedAtIsNull(userId, "SIMULATE")) {
            try {
                JsonNode content = mapper.readTree(
                        crypto.decryptUserField(userId, r.getContentEnc()));
                trainings.addObject()
                        .put("date", dateOf(r))
                        .put("avgScore", content.path("overall").path("avgScore").asInt(-1));
            } catch (Exception ignore) {
                // 解密失败（历史密钥）跳过
            }
        }
        return root;
    }

    /** 导出快照：属主全量数据（解密后）驻内存 TTL，一次性领取 */
    public String createExport(long userId, String ip) {
        purgeExpired();
        ObjectNode snapshot = mapper.createObjectNode();
        userRepo.findByIdAndDeletedAtIsNull(userId).ifPresent(u -> snapshot.put("nickname", u.getNickname()));
        snapshot.put("generatedAt", Instant.now().toString());
        snapshot.set("reports", allReportsDecrypted(userId));
        snapshot.set("emotionPoints", allTrajectory(userId));
        snapshot.set("profiles", allProfiles(userId));

        String fileId = Ulid.next();
        exports.put(fileId, new Snapshot(userId, snapshot, Instant.now().plus(EXPORT_TTL)));
        audit.record(userId, "EXPORT", "archive_export:" + fileId, ip);
        return fileId;
    }

    public ObjectNode downloadExport(long userId, String fileId, String ip) {
        Snapshot s = exports.get(fileId);
        if (s == null || s.expiresAt().isBefore(Instant.now())) {
            exports.remove(fileId);
            throw new BizException(ErrorCode.NOT_FOUND, "导出链接不存在或已过期（限时 5 分钟）");
        }
        if (s.userId() != userId) {
            throw new BizException(ErrorCode.FORBIDDEN);   // 属主断言：链接不可跨账号领取
        }
        exports.remove(fileId);                            // 一次性：领取即焚
        audit.record(userId, "EXPORT_DOWNLOAD", "archive_export:" + fileId, ip);
        return s.data();
    }

    // ---------------- internals ----------------

    private void purgeExpired() {
        Instant now = Instant.now();
        exports.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private ArrayNode reportMeta(long userId) {
        var arr = mapper.createArrayNode();
        reportRepo.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId,
                        org.springframework.data.domain.PageRequest.of(0, 100))
                .forEach(r -> arr.addObject()
                        .put("id", r.getId()).put("type", r.getType()).put("title", r.getTitle())
                        .put("riskLevel", r.getRiskLevel()));
        return arr;
    }

    private ArrayNode filterReportsInRange(long userId, LocalDate from, LocalDate to) {
        var arr = mapper.createArrayNode();
        for (ReportEntity r : reportRepo.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                userId, org.springframework.data.domain.PageRequest.of(0, 100)).getContent()) {
            LocalDate d = r.getCreatedAt() == null
                    ? null : LocalDate.ofInstant(r.getCreatedAt(), java.time.ZoneOffset.UTC);
            // H2 测试库 created_at 无默认值：null 视为"本次运行内"，保留以便冒烟
            if (d != null && (d.isBefore(from) || d.isAfter(to))) continue;
            ObjectNode n = arr.addObject().put("id", r.getId()).put("type", r.getType())
                    .put("title", r.getTitle()).put("riskLevel", r.getRiskLevel());
            n.put("date", d == null ? from.toString() : d.toString());
        }
        return arr;
    }

    private ArrayNode allReportsDecrypted(long userId) {
        var arr = mapper.createArrayNode();
        for (ReportEntity r : reportRepo.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                userId, org.springframework.data.domain.PageRequest.of(0, 200)).getContent()) {
            ObjectNode n = arr.addObject();
            n.put("id", r.getId()).put("type", r.getType()).put("title", r.getTitle())
                    .put("riskLevel", r.getRiskLevel()).put("date", dateOf(r));
            try {
                n.set("content", mapper.readTree(crypto.decryptUserField(userId, r.getContentEnc())));
            } catch (Exception e) {
                n.put("content", "（该报告无法解密：密钥已销毁）");
            }
        }
        return arr;
    }

    private ArrayNode allTrajectory(long userId) {
        var arr = mapper.createArrayNode();
        for (EmotionTrajectoryEntity t : trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
                userId, LocalDate.now().minusDays(365), LocalDate.now())) {
            ObjectNode n = arr.addObject();
            n.put("date", t.getRecordDate().toString()).put("emotion", t.getPrimaryEmotion())
                    .put("valence", t.getValence().toString()).put("intensity", t.getIntensity().toString())
                    .put("sourceType", t.getSourceType());
            readInto(n, "eventTags", t.getEventTags());
        }
        return arr;
    }

    private ArrayNode allProfiles(long userId) {
        var arr = mapper.createArrayNode();
        for (EmotionProfileEntity p : profileRepo.findByUserIdOrderByStatWeekDesc(userId)) {
            ObjectNode n = arr.addObject();
            n.put("statWeek", p.getStatWeek()).put("riskLevel", p.getRiskLevel());
            if (p.getAvgValence() != null) n.put("avgValence", p.getAvgValence().toString());
            readInto(n, "stressorTop", p.getStressorTopJson());
            readInto(n, "distortionTop", p.getDistortionTopJson());
        }
        return arr;
    }

    private void readInto(ObjectNode parent, String field, String json) {
        try {
            parent.set(field, json == null || json.isBlank() ? mapper.createArrayNode() : mapper.readTree(json));
        } catch (Exception e) {
            parent.set(field, mapper.createArrayNode());
        }
    }

    private String dateOf(ReportEntity r) {
        return r.getCreatedAt() == null ? "" : LocalDate.ofInstant(r.getCreatedAt(), java.time.ZoneOffset.UTC).toString();
    }
}
