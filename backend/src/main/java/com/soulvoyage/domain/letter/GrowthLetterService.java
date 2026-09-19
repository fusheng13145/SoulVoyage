package com.soulvoyage.domain.letter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.checkin.MoodCheckInRepository;
import com.soulvoyage.domain.diary.DiaryRepository;
import com.soulvoyage.domain.exercise.ExerciseRecordRepository;
import com.soulvoyage.domain.notify.UserPreferencesRepository;
import com.soulvoyage.domain.profile.EmotionProfileRepository;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.orchestrator.OrchestratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 下篇·G6 成长来信调度侧：每周一 07:00（业务时区）为上周有 ≥3 个数据点、且未关闭来信的用户，
 * 确定性组装"闭集素材"（原句片段只取自 ta 自己的日记/打卡一句话），提交 GROWTH_LETTER_PIPELINE。
 * clientReqId = letter:{week}:{userId} 幂等——重跑/重放不重复花 LLM 配额。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrowthLetterService {

    static final int MIN_DATA_POINTS = 3;

    private final UserPreferencesRepository prefsRepo;
    private final EmotionTrajectoryRepository trajRepo;
    private final DiaryRepository diaryRepo;
    private final MoodCheckInRepository checkInRepo;
    private final ExerciseRecordRepository exerciseRepo;
    private final EmotionProfileRepository profileRepo;
    private final GrowthLetterRepository letterRepo;
    private final CryptoService crypto;
    private final ObjectMapper mapper;
    private final BusinessCalendar cal;
    private final OrchestratorService orchestrator;

    @Scheduled(cron = "0 0 7 * * 1", zone = "${soulvoyage.business-zone:Asia/Shanghai}")
    public void mondaySweep() {
        try {
            int n = sweep(cal.today());
            log.info("growth letter sweep: {} letters submitted", n);
        } catch (Exception e) {
            log.error("growth letter sweep failed", e);
        }
    }

    /** 测试直调入口：runDate 所在周的前一周为来信周 */
    public int sweep(LocalDate runDate) {
        LocalDate target = runDate.minusWeeks(1);          // 上一个自然周
        WeekFields wf = WeekFields.ISO;
        LocalDate weekStart = target.with(wf.dayOfWeek(), 1);
        LocalDate weekEnd = weekStart.plusDays(6);
        String statWeek = target.get(wf.weekBasedYear()) + "-W"
                + String.format("%02d", target.get(wf.weekOfWeekBasedYear()));

        int submitted = 0;
        for (var p : prefsRepo.findByLetterOnOrderByUserId((short) 1)) {
            long userId = p.getUserId();
            try {
                if (letterRepo.findByUserIdAndStatWeek(userId, statWeek).isPresent()) continue;
                var traj = trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
                        userId, weekStart, weekEnd);
                if (traj.size() < MIN_DATA_POINTS) continue;   // 素材不足周不写信（宁缺毋滥）
                ObjectNode input = buildInput(userId, statWeek, weekStart, weekEnd, traj);
                orchestrator.submit(userId, "GROWTH_LETTER_PIPELINE", input,
                        "letter:" + statWeek + ":" + userId);
                submitted++;
            } catch (Exception e) {
                log.warn("letter submit failed user={}: {}", userId, e.toString());
            }
        }
        return submitted;
    }

    private ObjectNode buildInput(long userId, String statWeek,
                                  LocalDate from, LocalDate to,
                                  List<EmotionTrajectoryEntity> traj) {
        ObjectNode m = mapper.createObjectNode();
        double sum = 0;
        Map<String, Integer> emoCount = new HashMap<>();
        for (var t : traj) {
            sum += t.getValence() == null ? 0 : t.getValence().doubleValue();
            emoCount.merge(t.getPrimaryEmotion(), 1, Integer::sum);
        }
        m.put("dataPoints", traj.size());
        m.put("avgValence", round(sum / traj.size(), 2));
        var prev = trajRepo.findByUserIdAndRecordDateBetweenOrderByRecordDateAsc(
                userId, from.minusWeeks(1), to.minusWeeks(1));
        if (!prev.isEmpty()) {
            double ps = prev.stream()
                    .mapToDouble(x -> x.getValence() == null ? 0 : x.getValence().doubleValue()).average().orElse(0);
            m.put("prevAvgValence", round(ps, 2));
        }
        emoCount.entrySet().stream().max(Map.Entry.comparingByValue())
                .ifPresent(e -> m.put("emotionTop", e.getKey()));

        profileRepo.findByUserIdAndStatWeek(userId, statWeek).ifPresent(p -> {
            ArrayNode stressors = m.putArray("stressorTop");
            try {
                JsonNode arr = mapper.readTree(
                        p.getStressorTopJson() == null ? "[]" : p.getStressorTopJson());
                for (JsonNode n : arr) {
                    String s = n.path("name").asText(n.asText(""));
                    if (!s.isBlank() && stressors.size() < 3) stressors.add(s);
                }
            } catch (Exception ignore) { }
        });

        int exercisesDone = 0;
        for (var x : exerciseRepo.findByUserIdOrderByCreatedAtDescIdDesc(userId)) {
            if (x.getCreatedAt() == null) continue;
            LocalDate d = x.getCreatedAt().atZone(cal.zone()).toLocalDate();
            if (!d.isBefore(from) && !d.isAfter(to) && x.getCompleted() != null && x.getCompleted() == 1) {
                exercisesDone++;
            }
        }
        m.put("exercisesDone", exercisesDone);
        long checkinDays = checkInRepo.findByUserIdAndCheckDateBetweenOrderByCheckDateAsc(
                userId, from, to).size();
        m.put("streakDays", (int) checkinDays);

        ArrayNode quotes = m.putArray("quotes");
        for (var d : diaryRepo.findByUserIdAndDeletedAtIsNullOrderByRecordDateDescIdDesc(userId)) {
            if (quotes.size() >= 2) break;
            if (d.getRecordDate().isBefore(from) || d.getRecordDate().isAfter(to)) continue;
            try {
                String text = crypto.decryptUserField(userId, d.getContentEnc());
                String q = pickSentence(text);
                if (q != null) quotes.add(q);
            } catch (Exception ignore) { }
        }
        if (quotes.size() < 3) {
            for (var c : checkInRepo.findByUserIdAndCheckDateBetweenOrderByCheckDateAsc(userId, from, to)) {
                if (quotes.size() >= 3 || c.getNoteEnc() == null) continue;
                try {
                    String note = crypto.decryptUserField(userId, c.getNoteEnc());
                    if (note.length() <= 40 && !note.isBlank()) quotes.add(note);
                } catch (Exception ignore) { }
            }
        }
        m.put("insufficient", quotes.isEmpty());

        ObjectNode input = mapper.createObjectNode();
        input.put("statWeek", statWeek);
        input.set("material", m);
        return input;
    }

    /** 取文中最有画面感的一句（≤40 字）作闭集引文候选 */
    static String pickSentence(String text) {
        String best = null;
        for (String s : text.split("[。！？!?\\n]")) {
            String c = s.trim();
            if (c.length() < 6 || c.length() > 40) continue;
            if (best == null || c.length() > best.length()) best = c;
        }
        return best;
    }

    private static double round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    /** 读取侧：来信合集（解密回传，属主断言在 Controller 的 userId 过滤里） */
    public List<Map<String, Object>> list(long userId) {
        var out = new java.util.ArrayList<Map<String, Object>>();
        for (GrowthLetterEntity l : letterRepo.findByUserIdOrderByStatWeekDesc(userId)) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", "gl_" + l.getId());
            m.put("statWeek", l.getStatWeek());
            m.put("createdAt", l.getCreatedAt().toString());
            try {
                JsonNode c = mapper.readTree(crypto.decryptUserField(userId, l.getContentEnc()));
                m.put("letter", c.path("letter").asText(""));
                m.put("weekGlow", c.path("weekGlow").asText(""));
            } catch (Exception e) {
                m.put("letter", "（无法解密：密钥已销毁）");
                m.put("weekGlow", "");
            }
            out.add(m);
        }
        return out;
    }
}
