package com.soulvoyage.domain.checkin;

import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.domain.diary.DiaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * G3 Streak：连续记录天数（打卡或日记任一即算）。
 * 文案永远"你已经为自己记录了 N 天"——断签不羞辱，补签有救济（每自然月 1 次，在打卡服务里落）。
 */
@Service
@RequiredArgsConstructor
public class StreakService {

    public record StreakView(int current, int longest, int totalDays, boolean makeupAvailable) {}

    private final MoodCheckInRepository checkInRepo;
    private final DiaryRepository diaryRepo;
    private final BusinessCalendar cal;

    public StreakView compute(long userId) {
        Set<LocalDate> days = recordDays(userId);
        LocalDate today = cal.today();

        int current = 0;
        // 今日还没记录不算断签：从昨天往回数
        LocalDate cursor = days.contains(today) ? today : today.minusDays(1);
        while (days.contains(cursor)) {
            current++;
            cursor = cursor.minusDays(1);
        }

        int longest = 0;
        int run = 0;
        LocalDate prev = null;
        for (LocalDate d : new TreeSet<>(days)) {
            run = (prev != null && prev.plusDays(1).equals(d)) ? run + 1 : 1;
            longest = Math.max(longest, run);
            prev = d;
        }

        boolean makeupUsed = checkInRepo.findByUserIdOrderByCheckDateDesc(userId).stream()
                .filter(c -> c.getMadeUp() != null && c.getMadeUp() == 1)
                .anyMatch(c -> c.getCheckDate().getYear() == today.getYear()
                        && c.getCheckDate().getMonthValue() == today.getMonthValue());
        return new StreakView(current, longest, days.size(), !makeupUsed);
    }

    /** 补签救济是否可用于该日：仅往日前 14 天内、当日无任何记录、本月未用过补签 */
    public boolean makeupAllowed(long userId, LocalDate date) {
        LocalDate today = cal.today();
        if (date.isAfter(today.minusDays(1)) || date.isBefore(today.minusDays(14))) return false;
        if (recordDays(userId).contains(date)) return false;
        StreakView s = compute(userId);
        return s.makeupAvailable();
    }

    private Set<LocalDate> recordDays(long userId) {
        Set<LocalDate> days = new HashSet<>();
        for (MoodCheckInEntity c : checkInRepo.findByUserIdOrderByCheckDateAsc(userId)) {
            days.add(c.getCheckDate());
        }
        List<LocalDate> diaryDates = diaryRepo.findRecordDatesByUserId(userId);
        days.addAll(diaryDates);
        return days;
    }
}
