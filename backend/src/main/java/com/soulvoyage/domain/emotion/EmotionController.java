package com.soulvoyage.domain.emotion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.api.ApiResponse;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.domain.crisis.CrisisState;
import com.soulvoyage.domain.profile.EmotionProfileEntity;
import com.soulvoyage.domain.profile.EmotionProfileRepository;
import com.soulvoyage.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * UC3 情绪可视化数据源（手册 §6.3）：轨迹点 + 周画像。
 * 均为登录态 + 隐式属主（查询强制以 JWT userId 为键，天然防 IDOR）。
 * 数值点（效价/强度/情绪词）非隐私原文，可明文回传；事件标签本属用户自己的记录。
 */
@RestController
@RequestMapping("/api/v1/emotions")
@RequiredArgsConstructor
public class EmotionController {

    private final EmotionTrajectoryRepository trajRepo;
    private final EmotionProfileRepository profileRepo;
    private final UserRepository userRepo;
    private final ObjectMapper mapper;
    private final BusinessCalendar cal;

    @GetMapping("/trajectory")
    public ApiResponse<Map<String, Object>> trajectory(
            @AuthenticationPrincipal AuthPrincipal p,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        LocalDate end = to == null ? cal.today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        List<Map<String, Object>> points = trajRepo
                .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(p.userId(), start, end).stream()
                .map(t -> Map.<String, Object>of(
                        "date", t.getRecordDate().toString(),
                        "sourceType", t.getSourceType(),
                        "emotion", t.getPrimaryEmotion(),
                        "valence", t.getValence(),
                        "intensity", t.getIntensity(),
                        "eventTags", parseJson(t.getEventTags())))
                .toList();
        return ApiResponse.ok(Map.of("from", start.toString(), "to", end.toString(), "points", points));
    }

    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile(@AuthenticationPrincipal AuthPrincipal p) {
        List<Map<String, Object>> weeks = profileRepo.findByUserIdOrderByStatWeekDesc(p.userId()).stream()
                .map(w -> Map.<String, Object>of(
                        "statWeek", w.getStatWeek(),
                        "avgValence", w.getAvgValence() == null ? "" : w.getAvgValence(),
                        "riskLevel", w.getRiskLevel(),
                        "stressorTop", parseJson(w.getStressorTopJson()),
                        "distortionTop", parseJson(w.getDistortionTopJson())))
                .toList();
        String crisisState = userRepo.findById(p.userId())
                .map(u -> CrisisState.parse(u.getCrisisState()).name())
                .orElse(CrisisState.NORMAL.name());
        // crisisMode：处于危机生命周期任意活跃态（CRISIS 强干预 / COOLING 常驻横幅 / REVIEW 复核中）
        return ApiResponse.ok(Map.of(
                "crisisMode", CrisisState.parse(crisisState).active(),
                "crisisState", crisisState,
                "weeks", weeks));
    }

    private Object parseJson(String s) {
        if (s == null || s.isBlank()) return List.of();
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            return List.of();
        }
    }
}
