package com.soulvoyage.domain.achievement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.checkin.StreakService;
import com.soulvoyage.domain.companion.CompanionSessionRepository;
import com.soulvoyage.domain.diary.DiaryRepository;
import com.soulvoyage.domain.exercise.ExerciseRecordRepository;
import com.soulvoyage.domain.notify.NotificationService;
import com.soulvoyage.domain.profile.EmotionProfileEntity;
import com.soulvoyage.domain.profile.EmotionProfileRepository;
import com.soulvoyage.domain.report.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * G3 成就判定：幂等、确定性、不阻断主流程（每条规则单独吞异常——成就永远不值得弄挂业务动作）。
 * 解锁 → user_achievement 落库 + 通知中心 ACHIEVEMENT（前端经 GET /notifications 拉取后放 confetti-lite）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AchievementService {

    private final UserAchievementRepository achRepo;
    private final DiaryRepository diaryRepo;
    private final CompanionSessionRepository companionRepo;
    private final ExerciseRecordRepository exerciseRepo;
    private final ReportRepository reportRepo;
    private final EmotionProfileRepository profileRepo;
    private final StreakService streakService;
    private final NotificationService notify;
    private final CryptoService crypto;
    private final ObjectMapper mapper;
    private final BusinessCalendar cal;

    /** 在打卡/日记/漫聊/练习完成等"自我关照动作"后调用；单用户多次调用安全 */
    public List<String> evaluate(long userId) {
        List<String> unlocked = new java.util.ArrayList<>();
        Map<String, Predicate<Long>> rules = rules();
        for (var def : AchievementCatalog.ALL) {
            try {
                if (achRepo.findByUserIdAndCode(userId, def.code()).isPresent()) continue;
                if (rules.get(def.code()).test(userId)) {
                    UserAchievementEntity a = new UserAchievementEntity();
                    a.setUserId(userId);
                    a.setCode(def.code());
                    a.setUnlockedAt(java.time.Instant.now());
                    achRepo.save(a);
                    notify.push(userId, "ACHIEVEMENT", "ach:" + def.code(), "achievement",
                            Map.of("name", def.name(), "desc", def.desc()), "/me");
                    unlocked.add(def.code());
                    log.info("achievement unlocked user={} code={}", userId, def.code());
                }
            } catch (Exception e) {
                log.warn("achievement rule {} failed for user {}: {}", def.code(), userId, e.toString());
            }
        }
        return unlocked;
    }

    /** GET /achievements 用：目录 + 解锁状态 */
    public List<Map<String, Object>> wall(long userId) {
        var owned = new HashSet<String>();
        achRepo.findByUserIdOrderByUnlockedAtAsc(userId).forEach(a -> owned.add(a.getCode()));
        return AchievementCatalog.ALL.stream().map(d -> {
            java.time.Instant at = achRepo.findByUserIdAndCode(userId, d.code())
                    .map(UserAchievementEntity::getUnlockedAt).orElse(null);
            return Map.<String, Object>of(
                    "code", d.code(), "name", d.name(), "desc", d.desc(),
                    "unlocked", owned.contains(d.code()),
                    "unlockedAt", at == null ? "" : at.toString());
        }).toList();
    }

    private Map<String, Predicate<Long>> rules() {
        return Map.of(
                "FIRST_DIARY", u -> diaryRepo.countByUserIdAndDeletedAtIsNull(u) >= 1,
                "COMPANION_OPENED", u -> companionRepo.existsByUserId(u),
                "STREAK_7", u -> streakService.compute(u).current() >= 7,
                "STREAK_30", u -> streakService.compute(u).current() >= 30,
                "ALL_EXERCISES", u -> exerciseRepo.countDistinctCompletedExercises(u) >= 5,
                "FIRST_A_GRADE", this::hasAGradeTraining,
                "NO_REPEAT_DISTORTION", this::distortionGone
        );
    }

    /** 首次训练 A 档：SIMULATE 复盘报告 overall.avgScore ≥ 90（解密失败视为未达成） */
    private boolean hasAGradeTraining(long userId) {
        try {
            for (var r : reportRepo.findByUserIdAndTypeAndDeletedAtIsNull(userId, "SIMULATE")) {
                JsonNode content = mapper.readTree(crypto.decryptUserField(userId, r.getContentEnc()));
                if (content.path("overall").path("avgScore").asInt(0) >= 90) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 误区不再出现：最近画像的 Top1 误区，在其上一周画像中不存在（且上一周确有该维度数据） */
    private boolean distortionGone(long userId) {
        try {
            List<EmotionProfileEntity> weeks = profileRepo.findByUserIdOrderByStatWeekDesc(userId);
            if (weeks.size() < 2) return false;
            List<String> now = distortionNames(weeks.get(0));
            List<String> before = distortionNames(weeks.get(1));
            if (before.isEmpty() || now.isEmpty()) return false;
            return !before.contains(now.get(0));
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> distortionNames(EmotionProfileEntity p) {
        List<String> out = new java.util.ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(
                    p.getDistortionTopJson() == null ? "[]" : p.getDistortionTopJson());
            for (JsonNode n : arr) {
                String s = n.path("name").asText("");
                if (!s.isBlank()) out.add(s);
            }
        } catch (Exception ignored) { }
        return out;
    }
}
