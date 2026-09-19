package com.soulvoyage.agent.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.profile.EmotionProfileEntity;
import com.soulvoyage.domain.profile.EmotionProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "记得你"的上下文组装器（下篇·C0）：每轮 prompt 注入近 7 天画像摘要 + 事件标签——
 * 只喂聚合结论，不注入原文（日记/漫聊密文不进陪聊上下文，隐私面收窄）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompanionContextAssembler {

    public record ProfileBlock(String summary, String topEventTag) {}

    private final EmotionTrajectoryRepository trajRepo;
    private final EmotionProfileRepository profileRepo;
    private final BusinessCalendar cal;
    private final ObjectMapper mapper;

    public ProfileBlock assemble(long userId) {
        try {
            LocalDate today = cal.today();
            List<EmotionTrajectoryEntity> week = trajRepo
                    .findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(userId, today.minusDays(6), today);
            if (week.isEmpty()) {
                // 新用户没有"上周"，但有本周画像也够第一句回引用
                List<EmotionProfileEntity> ps = profileRepo.findByUserIdOrderByStatWeekDesc(userId);
                if (!ps.isEmpty()) return new ProfileBlock(profileSummary(ps.get(0)), topTagOf(ps.get(0)));
                return new ProfileBlock("", null);
            }
            Map<String, Integer> emotions = new LinkedHashMap<>();
            Map<String, Integer> tags = new LinkedHashMap<>();
            for (EmotionTrajectoryEntity t : week) {
                emotions.merge(t.getPrimaryEmotion(), 1, Integer::sum);
                for (JsonNode e : mapper.readTree(t.getEventTags() == null ? "[]" : t.getEventTags())) {
                    String tag = e.path("tag").asText("");
                    if (!tag.isBlank()) tags.merge(tag, 1, Integer::sum);
                }
            }
            String topEmotion = topKey(emotions);
            String topTag = topKey(tags);
            StringBuilder sb = new StringBuilder();
            sb.append("近 7 天主导情绪：").append(topEmotion == null ? "—" : topEmotion);
            if (topTag != null) sb.append("；高频事件：").append(topTag);
            return new ProfileBlock(sb.toString(), topTag);
        } catch (Exception e) {
            log.warn("companion profile assemble failed user={}: {}", userId, e.toString());
            return new ProfileBlock("", null);
        }
    }

    private String profileSummary(EmotionProfileEntity p) {
        StringBuilder sb = new StringBuilder();
        if (p.getAvgValence() != null) {
            double v = p.getAvgValence().doubleValue();
            sb.append("上周整体基调：").append(v > 0.15 ? "偏暖" : v < -0.15 ? "偏低" : "平稳");
        }
        return sb.toString();
    }

    private String topTagOf(EmotionProfileEntity p) {
        try {
            JsonNode arr = mapper.readTree(p.getStressorTopJson() == null ? "[]" : p.getStressorTopJson());
            if (arr.isArray() && !arr.isEmpty()) return arr.get(0).path("name").asText(null);
        } catch (Exception ignored) { }
        return null;
    }

    private String topKey(Map<String, Integer> m) {
        String best = null;
        int bestN = 0;
        List<Map.Entry<String, Integer>> es = new ArrayList<>(m.entrySet());
        for (Map.Entry<String, Integer> e : es) {
            if (e.getValue() > bestN) { best = e.getKey(); bestN = e.getValue(); }
        }
        return best;
    }
}
