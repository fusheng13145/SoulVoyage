package com.soulvoyage.domain.board;

import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.domain.checkin.MoodCheckInRepository;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.notify.UserPreferencesRepository;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * M12 辅导员群体看板（§11.3）：仅聚合统计 + 用户显式授权 + 最小人数阈值。
 * 三道隐私闸（全部在数据出口上，不靠调用方自觉）：
 * 1. 只聚合明文结构化列（打卡 rating/energy/emotion_code、轨迹效价、风险级别计数），
 *    ✦ 密文列（打卡备注/风险证据/日记正文）从不出现在任何查询里；
 * 2. 群体内授权人数 &lt; {@link #MIN_CONSENTED} 时整块看板抑制，只回抑制结论；
 * 3. 逐日均值再设贡献者下限 {@link #DAILY_MIN_CONTRIBUTORS}，防"群体达标但单日仅一人"从曲线上读出个体。
 */
@Service
@RequiredArgsConstructor
public class GroupBoardService {

    public static final int MIN_CONSENTED = 10;
    public static final int DAILY_MIN_CONTRIBUTORS = 3;

    private final SupportGroupRepository groups;
    private final SupportGroupMemberRepository members;
    private final UserPreferencesRepository prefs;
    private final UserRepository users;
    private final MoodCheckInRepository checkIns;
    private final EmotionTrajectoryRepository trajectory;
    private final RiskEventRepository riskEvents;
    private final BusinessCalendar cal;

    // ---------------- 群体维护（管理端动作，暴露的是成员身份而非心理数据） ----------------

    public List<Map<String, Object>> listGroups() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SupportGroupEntity g : groups.findAllByOrderByIdAsc()) {
            List<Long> ids = members.findUserIdsByGroupId(g.getId());
            int consented = consentedIds(ids).size();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("name", g.getName());
            m.put("status", g.getStatus().intValue());
            m.put("memberCount", ids.size());
            m.put("consentedCount", consented);
            m.put("suppressed", g.getStatus() != 1 || consented < MIN_CONSENTED);
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> detail(Long groupId) {
        SupportGroupEntity g = group(groupId);
        List<Long> ids = members.findUserIdsByGroupId(g.getId());
        Set<Long> consented = Set.copyOf(consentedIds(ids));
        List<Map<String, Object>> memberViews = new ArrayList<>();
        for (UserEntity u : users.findAllById(ids)) {
            memberViews.add(Map.of(
                    "userId", u.getId(),
                    "username", u.getUsername(),
                    "consented", consented.contains(u.getId())));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("name", g.getName());
        m.put("status", g.getStatus().intValue());
        m.put("memberCount", ids.size());
        m.put("consentedCount", consented.size());
        m.put("members", memberViews);
        return m;
    }

    @Transactional
    public Map<String, Object> createGroup(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 30) {
            throw new BizException(ErrorCode.BAD_PARAMS, "群体名不能为空且不超过 30 字");
        }
        if (groups.findByName(n).isPresent()) throw new BizException(ErrorCode.BAD_PARAMS, "已有同名群体");
        SupportGroupEntity g = new SupportGroupEntity();
        g.setName(n);
        groups.save(g);
        return detail(g.getId());
    }

    @Transactional
    public Map<String, Object> addMembers(Long groupId, List<Long> userIds) {
        SupportGroupEntity g = group(groupId);
        if (userIds == null || userIds.isEmpty()) throw new BizException(ErrorCode.BAD_PARAMS, "请选择要加入的用户");
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > 50) throw new BizException(ErrorCode.BAD_PARAMS, "一次最多添加 50 人");
        for (Long uid : ids) {
            UserEntity u = users.findById(uid)
                    .orElseThrow(() -> new BizException(ErrorCode.BAD_PARAMS, "用户 " + uid + " 不存在"));
            if (u.getDeletedAt() != null) throw new BizException(ErrorCode.BAD_PARAMS, "已注销用户不能加入群体");
            if (!members.existsByGroupIdAndUserId(g.getId(), uid)) {
                SupportGroupMemberEntity m = new SupportGroupMemberEntity();
                m.setGroupId(g.getId());
                m.setUserId(uid);
                members.save(m);
            }
        }
        return detail(groupId);
    }

    @Transactional
    public void removeMember(Long groupId, Long userId) {
        group(groupId);
        members.deleteByGroupIdAndUserId(groupId, userId);
    }

    // ---------------- 看板聚合（唯一的数据出口） ----------------

    public Map<String, Object> stats(Long groupId, int days) {
        SupportGroupEntity g = group(groupId);
        int span = Math.min(Math.max(days, 1), 90);
        List<Long> ids = members.findUserIdsByGroupId(g.getId());
        List<Long> consented = consentedIds(ids);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("groupId", g.getId());
        resp.put("groupName", g.getName());
        resp.put("windowDays", span);
        resp.put("memberCount", ids.size());
        resp.put("consentedCount", consented.size());
        resp.put("threshold", MIN_CONSENTED);
        boolean inactive = g.getStatus() != 1;
        boolean suppressed = inactive || consented.size() < MIN_CONSENTED;
        resp.put("suppressed", suppressed);
        if (suppressed) {
            resp.put("reason", inactive ? "该群体已停用"
                    : "群体内已授权参与群体统计的成员不足 " + MIN_CONSENTED + " 人，看板保持抑制（防个体反推）");
            return resp;
        }

        LocalDate today = LocalDate.now(cal.zone());
        LocalDate from = today.minusDays(span - 1L);

        Object[] agg = checkIns.aggregateForUsers(consented, from, today).get(0);
        List<Map<String, Object>> emotions = new ArrayList<>();
        for (Object[] r : checkIns.emotionDistributionForUsers(consented, from, today)) {
            emotions.add(Map.of("code", (String) r[0], "count", ((Number) r[1]).longValue()));
        }
        Map<String, Object> checkInStats = new LinkedHashMap<>();
        checkInStats.put("contributors", ((Number) agg[0]).longValue());
        checkInStats.put("personDays", ((Number) agg[1]).longValue());
        checkInStats.put("avgRating", agg[2] == null ? null : round(((Number) agg[2]).doubleValue(), 2));
        checkInStats.put("avgEnergy", agg[3] == null ? null : round(((Number) agg[3]).doubleValue(), 2));
        checkInStats.put("emotions", emotions);
        resp.put("checkIns", checkInStats);

        List<Map<String, Object>> byDay = new ArrayList<>();
        int hiddenDays = 0;
        for (Object[] r : trajectory.dailyValenceForUsers(consented, from, today)) {
            if (((Number) r[2]).longValue() < DAILY_MIN_CONTRIBUTORS) {
                hiddenDays++;   // 单日贡献者不足即整日隐藏，不回数值也不回人数
                continue;
            }
            byDay.add(Map.of("date", String.valueOf(r[0]),
                    "avgValence", round(((Number) r[1]).doubleValue(), 3)));
        }
        resp.put("valenceByDay", byDay);
        resp.put("hiddenDays", hiddenDays);

        Map<String, Long> risk = new TreeMap<>();
        for (Object[] r : riskEvents.countByLevelSinceForUsers(consented,
                from.atStartOfDay(cal.zone()).toInstant())) {
            risk.put((String) r[0], ((Number) r[1]).longValue());
        }
        resp.put("riskEvents", risk);
        return resp;
    }

    // ---------------- internals ----------------

    /** 授权交集：偏好行只在用户首次读/写设置时才存在，无行=从未开过=不授权（默认关即隐私默认） */
    private List<Long> consentedIds(List<Long> ids) {
        return ids.isEmpty() ? List.of() : prefs.findBoardConsentedUserIds(ids);
    }

    private SupportGroupEntity group(Long id) {
        return groups.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "群体不存在"));
    }

    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}
