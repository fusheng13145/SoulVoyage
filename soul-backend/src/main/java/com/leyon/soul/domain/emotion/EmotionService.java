package com.leyon.soul.domain.emotion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.soul.common.api.ErrorCode;
import com.leyon.soul.common.exception.BizException;
import com.leyon.soul.common.time.BusinessCalendar;
import com.leyon.soul.domain.crisis.CrisisState;
import com.leyon.soul.domain.emotion.dto.EmotionDtos.ProfileResp;
import com.leyon.soul.domain.emotion.dto.EmotionDtos.ProfileWeek;
import com.leyon.soul.domain.emotion.dto.EmotionDtos.SelfRatingReq;
import com.leyon.soul.domain.emotion.dto.EmotionDtos.SelfRatingResp;
import com.leyon.soul.domain.emotion.dto.EmotionDtos.TrajectoryPoint;
import com.leyon.soul.domain.emotion.dto.EmotionDtos.TrajectoryResp;
import com.leyon.soul.domain.profile.EmotionProfileRepository;
import com.leyon.soul.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * UC3 情绪查询与打卡（手册 §6.3）：轨迹聚合、周画像、轻量打卡。
 * Controller 仅做路由，读路径全部以 JWT userId 为键，天然防 IDOR。
 */
@Service
@RequiredArgsConstructor
public class EmotionService {

    private final EmotionTrajectoryRepository trajRepo;
    private final EmotionProfileRepository profileRepo;
    private final UserRepository userRepo;
    private final ObjectMapper mapper;
    private final BusinessCalendar cal;

    /** O2：range 14/30/90（默认 30）+ granularity=day|week；显式 from/to 亦钳制跨度 ≤90 天。 */
    public TrajectoryResp trajectory(long userId, LocalDate from, LocalDate to,
                                     int range, String granularity) {
        LocalDate end = to == null ? cal.today() : to;
        LocalDate start = from == null ? end.minusDays(Math.min(Math.max(range, 1), 90) - 1L) : from;
        if (start.plusDays(90).isBefore(end)) start = end.minusDays(90);
        List<EmotionTrajectoryEntity> rows = trajRepo
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(userId, start, end);
        List<TrajectoryPoint> points = "week".equals(granularity) ? weekly(rows) : daily(rows);
        return new TrajectoryResp(start.toString(), end.toString(), points);
    }

    private List<TrajectoryPoint> daily(List<EmotionTrajectoryEntity> rows) {
        return rows.stream()
                .map(t -> new TrajectoryPoint(
                        t.getRecordDate().toString(),
                        t.getSourceType(),
                        t.getPrimaryEmotion(),
                        t.getValence(),
                        t.getIntensity(),
                        parseJson(t.getEventTags())))
                .toList();
    }

    /** 周聚合：效价/强度取均值，情绪取众数，eventTags 不随周上卷。 */
    private List<TrajectoryPoint> weekly(List<EmotionTrajectoryEntity> rows) {
        var weeks = new TreeMap<LocalDate, List<EmotionTrajectoryEntity>>();
        for (EmotionTrajectoryEntity t : rows) {
            LocalDate ws = t.getRecordDate()
                    .with(java.time.temporal.WeekFields.ISO.dayOfWeek(), 1);
            weeks.computeIfAbsent(ws, k -> new java.util.ArrayList<>()).add(t);
        }
        return weeks.entrySet().stream().map(e -> {
            List<EmotionTrajectoryEntity> g = e.getValue();
            var emotion = g.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            EmotionTrajectoryEntity::getPrimaryEmotion,
                            java.util.stream.Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("");
            return new TrajectoryPoint(
                    e.getKey().toString(),
                    "WEEK",
                    emotion,
                    avg(g, true),
                    avg(g, false),
                    mapper.createArrayNode());
        }).toList();
    }

    private BigDecimal avg(List<EmotionTrajectoryEntity> g, boolean valence) {
        double m = g.stream().mapToDouble(t -> (valence ? t.getValence() : t.getIntensity())
                .doubleValue()).average().orElse(0);
        return BigDecimal.valueOf(m).setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * M6 轻量打卡（SELF_RATING）：今日页情绪盘直写轨迹点，不等任务管线。
     * M7 会升级为完整打卡表，本接口保持同一 sourceType 以兼容下游消费。
     */
    @Transactional
    public SelfRatingResp selfRating(long userId, SelfRatingReq req) {
        String emotion = req.emotion() == null ? "" : req.emotion().trim();
        if (emotion.isEmpty() || emotion.length() > 32)
            throw new BizException(ErrorCode.BAD_PARAMS, "emotion 需为 1-32 字");
        if (req.valence() == null || req.valence().abs().compareTo(BigDecimal.ONE) > 0)
            throw new BizException(ErrorCode.BAD_PARAMS, "valence 需在 [-1,1]");
        if (req.intensity() == null || req.intensity().compareTo(BigDecimal.ZERO) < 0
                || req.intensity().compareTo(BigDecimal.ONE) > 0)
            throw new BizException(ErrorCode.BAD_PARAMS, "intensity 需在 [0,1]");
        LocalDate today = cal.today();
        LocalDate date = req.date() == null ? today : req.date();
        if (date.isAfter(today))
            throw new BizException(ErrorCode.BAD_PARAMS, "打卡日期不能晚于今天");
        String note = req.note() == null ? null : req.note().trim();
        if (note != null && note.isEmpty()) note = null;
        if (note != null && note.length() > 200)
            throw new BizException(ErrorCode.BAD_PARAMS, "一句话最多 200 字");

        EmotionTrajectoryEntity t = new EmotionTrajectoryEntity();
        t.setUserId(userId);
        t.setRecordDate(date);
        t.setSourceType("SELF_RATING");
        t.setPrimaryEmotion(emotion);
        t.setValence(req.valence().setScale(3, RoundingMode.HALF_UP));
        t.setIntensity(req.intensity().setScale(2, RoundingMode.HALF_UP));
        if (note != null) t.setEventTags(mapper.createArrayNode().add(note).toString());
        t = trajRepo.save(t);
        return new SelfRatingResp(t.getId(), date.toString());
    }

    /** O2：近 N 周参数（默认 12，钳制 1..52），DB 层 limit。 */
    public ProfileResp profile(long userId, int weeks) {
        List<ProfileWeek> weekList = profileRepo.findByUserIdOrderByStatWeekDesc(
                        userId, PageRequest.of(0, Math.min(Math.max(weeks, 1), 52))).stream()
                .map(w -> new ProfileWeek(
                        w.getStatWeek(),
                        w.getAvgValence() == null ? "" : w.getAvgValence(),
                        w.getRiskLevel(),
                        parseJson(w.getStressorTopJson()),
                        parseJson(w.getDistortionTopJson())))
                .toList();
        String crisisState = userRepo.findById(userId)
                .map(u -> CrisisState.parse(u.getCrisisState()).name())
                .orElse(CrisisState.NORMAL.name());
        // crisisMode：处于危机生命周期任意活跃态（CRISIS 强干预 / COOLING 常驻横幅 / REVIEW 复核中）
        return new ProfileResp(CrisisState.parse(crisisState).active(), crisisState, weekList);
    }

    private JsonNode parseJson(String s) {
        if (s == null || s.isBlank()) return mapper.createArrayNode();
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }
}
