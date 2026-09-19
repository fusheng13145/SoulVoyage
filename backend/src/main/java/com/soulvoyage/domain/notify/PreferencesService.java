package com.soulvoyage.domain.notify;

import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * G5 偏好服务端真源：提醒开关/时间、来信开关、触感、漫聊分析总开关。
 * 首次读取即落一行默认值（UK(user) 幂等），前端 localStorage 降级为缓存。
 */
@Service
@RequiredArgsConstructor
public class PreferencesService {

    private static final Set<String> THEMES = Set.of("system", "light", "dark");
    private static final Set<String> KEYS = Set.of("theme", "checkinReminderOn", "reminderTime",
            "planReminderOn", "letterOn", "hapticOn", "companionAnalysisOn");

    private final UserPreferencesRepository repo;

    public Map<String, Object> get(long userId) {
        return toView(of(userId));
    }

    @Transactional
    public Map<String, Object> update(long userId, Map<String, Object> patch) {
        UserPreferencesEntity p = of(userId);
        for (var e : patch.entrySet()) {
            if (!KEYS.contains(e.getKey())) continue;   // 未知键静默忽略，向前兼容
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue());
            switch (e.getKey()) {
                case "theme" -> {
                    if (!THEMES.contains(v)) throw new BizException(ErrorCode.BAD_PARAMS, "不认识这个主题");
                    p.setTheme(v);
                }
                case "reminderTime" -> {
                    if (!v.matches("\\d{2}:\\d{2}") || Integer.parseInt(v.substring(0, 2)) > 23
                            || Integer.parseInt(v.substring(3)) > 59) {
                        throw new BizException(ErrorCode.BAD_PARAMS, "提醒时间格式应为 HH:MM");
                    }
                    p.setReminderTime(v);
                }
                default -> {
                    short flag = switch (v) {
                        case "1", "true" -> 1;
                        case "0", "false" -> 0;
                        default -> throw new BizException(ErrorCode.BAD_PARAMS, "开关值应为 true/false");
                    };
                    switch (e.getKey()) {
                        case "checkinReminderOn" -> p.setCheckinReminderOn(flag);
                        case "planReminderOn" -> p.setPlanReminderOn(flag);
                        case "letterOn" -> p.setLetterOn(flag);
                        case "hapticOn" -> p.setHapticOn(flag);
                        case "companionAnalysisOn" -> p.setCompanionAnalysisOn(flag);
                        default -> { }
                    }
                }
            }
        }
        return toView(repo.save(p));
    }

    private UserPreferencesEntity of(long userId) {
        return repo.findByUserId(userId).orElseGet(() -> {
            UserPreferencesEntity n = new UserPreferencesEntity();
            n.setUserId(userId);
            return repo.save(n);
        });
    }

    private Map<String, Object> toView(UserPreferencesEntity p) {
        Map<String, Object> m = new HashMap<>();
        m.put("theme", p.getTheme());
        m.put("checkinReminderOn", p.getCheckinReminderOn() == 1);
        m.put("reminderTime", p.getReminderTime());
        m.put("planReminderOn", p.getPlanReminderOn() == 1);
        m.put("letterOn", p.getLetterOn() == 1);
        m.put("hapticOn", p.getHapticOn() == 1);
        m.put("companionAnalysisOn", p.getCompanionAnalysisOn() == 1);
        m.put("updatedAt", p.getUpdatedAt() == null ? "" : p.getUpdatedAt().toString());
        return m;
    }
}
