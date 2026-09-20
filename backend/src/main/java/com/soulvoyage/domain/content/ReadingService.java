package com.soulvoyage.domain.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.domain.content.ContentStore.PsyEntry;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.profile.EmotionProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * N3 每日一读（手册·下篇 N3）：从 PsyTopic 内容池按"画像相关优先 + 历史去重"纯规则发 1 篇/日，
 * 不过 LLM（成本控制 + 零幻觉面）。同日重复请求返回同一篇；收藏进档案导出。
 */
@Service
@RequiredArgsConstructor
public class ReadingService {

    public static final String FAV_PSY = "PSY_TOPIC";

    private final ContentStore store;
    private final UserReadLogRepository readRepo;
    private final UserFavoriteRepository favRepo;
    private final EmotionProfileRepository profileRepo;
    private final EmotionTrajectoryRepository trajRepo;
    private final BusinessCalendar cal;
    private final ObjectMapper mapper;

    /** 今日一读：解析已发放的当日记录，否则按规则选新篇并留痕 */
    public Map<String, Object> today(long userId) {
        LocalDate day = cal.today();
        List<PsyEntry> pool = store.psyTopics();
        if (pool.isEmpty()) throw new BizException(ErrorCode.NOT_FOUND, "科普内容池暂无在线篇目");

        for (var log : readRepo.findByUserIdAndReadDate(userId, day)) {
            var hit = pool.stream().filter(p -> p.kgNodeId().equals(log.getCode())).findFirst();
            if (hit.isPresent()) return card(hit.get(), userId, day, false);
        }

        Set<String> seen = readRepo.findByUserIdOrderByReadDateDescIdDesc(userId).stream()
                .map(UserReadLogEntity::getCode).collect(Collectors.toSet());
        var candidates = pool.stream().filter(p -> !seen.contains(p.kgNodeId())).toList();
        if (candidates.isEmpty()) candidates = pool;   // 全读完一轮：重新轮换，仍按画像相关度排

        List<String> stressors = topStressors(userId);
        String emotion = recentEmotion(userId);
        PsyEntry pick = candidates.stream()
                .max(Comparator.comparingInt((PsyEntry p) -> relevance(p, stressors, emotion))
                        .thenComparing(PsyEntry::kgNodeId, Comparator.reverseOrder()))
                .orElseThrow();

        UserReadLogEntity log = new UserReadLogEntity();
        log.setUserId(userId);
        log.setCode(pick.kgNodeId());
        log.setReadDate(day);
        readRepo.save(log);
        return card(pick, userId, day, true);
    }

    /** 收藏开关：返回操作后是否已收藏 */
    public boolean toggleFavorite(long userId, String type, String refCode) {
        if (!FAV_PSY.equals(type))
            throw new BizException(ErrorCode.BAD_PARAMS, "暂只支持收藏 " + FAV_PSY);
        if (refCode == null || refCode.isBlank())
            throw new BizException(ErrorCode.BAD_PARAMS, "refCode 不能为空");
        var existing = favRepo.findByUserIdAndTypeAndRefCode(userId, type, refCode);
        if (existing.isPresent()) {
            favRepo.delete(existing.get());
            return false;
        }
        store.psyTopics().stream().filter(p -> p.kgNodeId().equals(refCode)).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "收藏的内容不存在或已下架"));
        UserFavoriteEntity f = new UserFavoriteEntity();
        f.setUserId(userId);
        f.setType(type);
        f.setRefCode(refCode);
        favRepo.save(f);
        return true;
    }

    public List<Map<String, Object>> favorites(long userId) {
        var pool = store.psyTopics();
        return favRepo.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .filter(f -> FAV_PSY.equals(f.getType()))
                .map(f -> {
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("type", f.getType());
                    n.put("refCode", f.getRefCode());
                    pool.stream().filter(p -> p.kgNodeId().equals(f.getRefCode())).findFirst()
                            .ifPresent(p -> {
                                n.put("title", p.title());
                                n.put("summary", p.summary());
                            });
                    return n;
                })
                .toList();
    }

    /** 档案导出用（S2 可携带权）：收藏原样列入个人数据 */
    public List<Map<String, Object>> favoritesForExport(long userId) {
        return favorites(userId);
    }

    // ---------------- internals ----------------

    private Map<String, Object> card(PsyEntry p, long userId, LocalDate day, boolean firstToday) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("kgNodeId", p.kgNodeId());
        n.put("title", p.title());
        n.put("summary", p.summary());
        n.put("microAction", p.microAction());
        n.put("aboutTags", p.aboutTags());
        n.put("readingSec", p.readingSec());
        n.put("date", day.toString());
        n.put("favorited", favRepo.findByUserIdAndTypeAndRefCode(userId, FAV_PSY, p.kgNodeId()).isPresent());
        n.put("firstToday", firstToday);
        return n;
    }

    /** 近 4 周画像压力源 Top（stressorTopJson: [{name,count}]，每周一至多 3 个） */
    private List<String> topStressors(long userId) {
        Map<String, Integer> counts = new HashMap<>();
        profileRepo.findByUserIdOrderByStatWeekDesc(userId).stream().limit(4).forEach(w -> {
            try {
                for (JsonNode s : mapper.readTree(
                        w.getStressorTopJson() == null ? "[]" : w.getStressorTopJson())) {
                    counts.merge(s.path("name").asText(), s.path("count").asInt(1), Integer::sum);
                }
            } catch (Exception ignore) {
                // 坏 JSON 视作无画像信号
            }
        });
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3).map(Map.Entry::getKey).toList();
    }

    /** 近 7 天最高频主导情绪（aboutTags 同时覆盖情绪词，故一并计分） */
    private String recentEmotion(long userId) {
        LocalDate day = cal.today();
        Map<String, Integer> counts = new HashMap<>();
        trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
                        userId, day.minusDays(6), day)
                .forEach(t -> counts.merge(t.getPrimaryEmotion(), 1, Integer::sum));
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private int relevance(PsyEntry p, List<String> stressors, String emotion) {
        int score = 0;
        for (String s : stressors) if (p.aboutTags().contains(s)) score += 2;
        if (emotion != null && p.aboutTags().contains(emotion)) score += 1;
        return score;
    }
}
