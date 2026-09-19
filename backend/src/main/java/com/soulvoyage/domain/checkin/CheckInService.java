package com.soulvoyage.domain.checkin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.achievement.AchievementService;
import com.soulvoyage.domain.emotion.EmotionCatalog;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * G1 每日心情打卡：10 秒低门槛记录（16 情绪盘 + 能量 1-5 + 可选一句话）。
 * 落 mood_check_in（每日一条可改，UK 防重）并同步 upsert emotion_trajectory(SELF_RATING)——
 * 情绪曲线从"写日记才有"变成"每天打卡就有"，画像/曲线/热力图数据密度随之提升。
 * 打卡即"自我关照动作"：随后跑一次成就判定（幂等、不阻断）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckInService {

    private final MoodCheckInRepository repo;
    private final EmotionTrajectoryRepository trajRepo;
    private final CryptoService crypto;
    private final BusinessCalendar cal;
    private final ObjectMapper mapper;
    private final StreakService streak;
    private final AchievementService achievements;

    public record CheckInReq(String emotion, Integer rating, Integer energy, String note) {}

    public record CheckInView(LocalDate date, String emotionCode, Integer rating, Integer energy,
                              String note, boolean madeUp, BigDecimal valence) {}

    @Transactional
    public CheckInView checkIn(long userId, LocalDate date, CheckInReq req) {
        return upsert(userId, date, req, false);
    }

    /** G3 补签救济：仅往日、14 天内、每自然月 1 次（"断签不羞辱"） */
    @Transactional
    public CheckInView makeup(long userId, LocalDate date, CheckInReq req) {
        if (!streak.makeupAllowed(userId, date)) {
            throw new BizException(ErrorCode.BAD_PARAMS, "补签只支持最近 14 天内缺记录的日子，且每月一次");
        }
        return upsert(userId, date, req, true);
    }

    public List<CheckInView> month(long userId, int year, int month) {
        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to = from.plusMonths(1).minusDays(1);
        List<CheckInView> out = new ArrayList<>();
        for (MoodCheckInEntity c : repo.findByUserIdAndCheckDateBetweenOrderByCheckDateAsc(userId, from, to)) {
            out.add(view(userId, c));
        }
        return out;
    }

    public CheckInView today(long userId) {
        return repo.findByUserIdAndCheckDate(userId, cal.today())
                .map(c -> view(userId, c)).orElse(null);
    }

    // ---------------- internals ----------------

    private CheckInView upsert(long userId, LocalDate date, CheckInReq req, boolean madeUp) {
        LocalDate today = cal.today();
        LocalDate d = date == null ? today : date;
        if (d.isAfter(today)) throw new BizException(ErrorCode.BAD_PARAMS, "未来的日子还没发生，先记今天");
        var emo = EmotionCatalog.byCode(req.emotion())
                .orElseThrow(() -> new BizException(ErrorCode.BAD_PARAMS, "不认识这个情绪表情，请重新选择"));
        if (req.rating() != null && (req.rating() < 1 || req.rating() > 5))
            throw new BizException(ErrorCode.BAD_PARAMS, "心情评分需在 1-5");
        if (req.energy() != null && (req.energy() < 1 || req.energy() > 5))
            throw new BizException(ErrorCode.BAD_PARAMS, "能量值需在 1-5");
        String note = req.note() == null ? null : req.note().trim();
        if (note != null && note.isEmpty()) note = null;
        if (note != null && note.length() > 200)
            throw new BizException(ErrorCode.BAD_PARAMS, "一句话最多 200 字");

        MoodCheckInEntity c = repo.findByUserIdAndCheckDate(userId, d).orElseGet(() -> {
            MoodCheckInEntity n = new MoodCheckInEntity();
            n.setUserId(userId);
            n.setCheckDate(d);
            return n;
        });
        c.setEmotionCode(emo.code());
        if (req.rating() != null) c.setRating(req.rating().shortValue());
        if (req.energy() != null) c.setEnergy(req.energy().shortValue());
        c.setNoteEnc(note == null ? null : crypto.encryptUserField(userId, note));
        if (madeUp) c.setMadeUp((short) 1);
        c = repo.save(c);

        syncSelfRatingPoint(userId, d, emo, note);
        try {
            achievements.evaluate(userId);   // 成就判定失败不影响打卡本身
        } catch (Exception e) {
            log.warn("achievement eval after checkin failed user={}", userId, e);
        }
        return view(userId, c);
    }

    /** 同步 SELF_RATING 轨迹点：当日仅保留一条（覆盖式），供曲线/画像/热力图统一消费 */
    private void syncSelfRatingPoint(long userId, LocalDate date,
                                     EmotionCatalog.Emotion emo, String note) {
        List<EmotionTrajectoryEntity> existing =
                trajRepo.findByUserIdAndRecordDateAndSourceType(userId, date, "SELF_RATING");
        EmotionTrajectoryEntity t;
        if (existing.isEmpty()) {
            t = new EmotionTrajectoryEntity();
            t.setUserId(userId);
            t.setRecordDate(date);
            t.setSourceType("SELF_RATING");
        } else {
            t = existing.get(0);
            if (existing.size() > 1) {   // M6 兼容期历史重复点：合并为一条
                trajRepo.deleteAll(existing.subList(1, existing.size()));
            }
        }
        t.setPrimaryEmotion(emo.label());
        t.setValence(BigDecimal.valueOf(emo.valence()).setScale(3, RoundingMode.HALF_UP));
        t.setIntensity(BigDecimal.valueOf(emo.intensity()).setScale(2, RoundingMode.HALF_UP));
        t.setEventTags(note == null ? "[]" : mapper.createArrayNode().add(note).toString());
        trajRepo.save(t);
    }

    private CheckInView view(long userId, MoodCheckInEntity c) {
        String note = null;
        if (c.getNoteEnc() != null) {
            try {
                note = crypto.decryptUserField(userId, c.getNoteEnc());
            } catch (Exception e) {
                note = "（无法显示：密钥已销毁）";
            }
        }
        var emo = EmotionCatalog.byCode(c.getEmotionCode());
        BigDecimal valence = emo.map(e -> BigDecimal.valueOf(e.valence())).orElse(BigDecimal.ZERO);
        return new CheckInView(c.getCheckDate(), c.getEmotionCode(),
                c.getRating() == null ? null : c.getRating().intValue(),
                c.getEnergy() == null ? null : c.getEnergy().intValue(),
                note, c.getMadeUp() != null && c.getMadeUp() == 1, valence);
    }
}
