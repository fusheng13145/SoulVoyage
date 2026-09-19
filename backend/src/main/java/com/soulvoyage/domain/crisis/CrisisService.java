package com.soulvoyage.domain.crisis;

import com.soulvoyage.audit.AuditService;
import com.soulvoyage.domain.profile.EmotionProfileEntity;
import com.soulvoyage.domain.profile.EmotionProfileRepository;
import com.soulvoyage.domain.risk.RiskEventEntity;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;

/**
 * 危机生命周期状态机服务（手册 下篇·S1）。
 * 修复"一次误触、永久降级"：crisis_flag 布尔位升级为 NORMAL→CRISIS→COOLING→NORMAL 的可恢复状态机，
 * 强干预窗口（仅 CRISIS 态）结束后链路恢复完整流水线——危机用户更需要被看见，而不是被关掉功能。
 * 迁移全部留痕 crisis_lifecycle（append-only），巡检由 {@link DailySweeper} 每日触发。
 */
@Slf4j
@Service
public class CrisisService {

    private final UserRepository userRepo;
    private final CrisisLifecycleRepository lifecycleRepo;
    private final RiskEventRepository riskEventRepo;
    private final EmotionProfileRepository profileRepo;
    private final AuditService audit;
    private final ZoneId zone;

    /** 强干预（CRISIS）窗口时长（默认 7 天；测试可配 "2s" 等） */
    private final Duration cooldown;
    /** 冷却自动解除：近 N 个完整自然周画像 avgValence 均须高于该阈值 */
    private final double downgradeThreshold;
    private final int downgradeWeeks;

    public CrisisService(UserRepository userRepo, CrisisLifecycleRepository lifecycleRepo,
                         RiskEventRepository riskEventRepo, EmotionProfileRepository profileRepo,
                         AuditService audit,
                         @Value("${soulvoyage.business-zone:Asia/Shanghai}") String businessZone,
                         @Value("${soulvoyage.crisis.cooldown:7d}") Duration cooldown,
                         @Value("${soulvoyage.crisis.downgrade-threshold:-0.3}") double downgradeThreshold,
                         @Value("${soulvoyage.crisis.downgrade-weeks:2}") int downgradeWeeks) {
        this.userRepo = userRepo;
        this.lifecycleRepo = lifecycleRepo;
        this.riskEventRepo = riskEventRepo;
        this.profileRepo = profileRepo;
        this.audit = audit;
        this.zone = ZoneId.of(businessZone);
        this.cooldown = cooldown;
        this.downgradeThreshold = downgradeThreshold;
        this.downgradeWeeks = downgradeWeeks;
    }

    public CrisisState stateOf(long userId) {
        return userRepo.findById(userId)
                .map(u -> CrisisState.parse(u.getCrisisState())).orElse(CrisisState.NORMAL);
    }

    // ---------------- 迁移入口 ----------------

    /** RISK_ARCHIVE 判 HIGH 后调用（替代旧的 markCrisis）：NORMAL→CRISIS；COOLING 期间→重新计时（RE_ENTRY） */
    @Transactional
    public void onHighRisk(long userId, Long eventId) {
        UserEntity u = userRepo.findById(userId).orElse(null);
        if (u == null) return;
        CrisisState cur = CrisisState.parse(u.getCrisisState());
        Instant now = Instant.now();
        switch (cur) {
            case NORMAL -> {
                u.setCrisisState(CrisisState.CRISIS.name());
                u.setCrisisStartedAt(now);
                u.setCrisisEndsAt(now.plus(cooldown));
                record(u.getId(), CrisisState.NORMAL, CrisisState.CRISIS, "HIGH_DETECTED", eventId, null);
                log.warn("user {} entered CRISIS, strong-intervention window {}", u.getId(), cooldown);
            }
            case COOLING -> {
                u.setCrisisState(CrisisState.CRISIS.name());
                u.setCrisisEndsAt(now.plus(cooldown));
                record(u.getId(), CrisisState.COOLING, CrisisState.CRISIS, "RE_ENTRY", eventId, null);
                log.warn("user {} RE-ENTRY into CRISIS during cooling", u.getId());
            }
            case CRISIS -> {
                // 强干预窗口内再次 HIGH：延长计时，不另计次
                u.setCrisisEndsAt(now.plus(cooldown));
                record(u.getId(), CrisisState.CRISIS, CrisisState.CRISIS, "HIGH_REFRESH", eventId, null);
            }
            case REVIEW -> record(u.getId(), CrisisState.REVIEW, CrisisState.REVIEW,
                    "HIGH_DURING_REVIEW", eventId, null);
        }
        userRepo.save(u);
    }

    /** 每日巡检（下篇·S1）：CRISIS 届满→COOLING（或累计 2 次→REVIEW）；COOLING 达标→NORMAL（crisis_end 留痕） */
    @Transactional
    public void sweep() {
        Instant now = Instant.now();
        for (UserEntity u : userRepo.findByCrisisStateAndCrisisEndsAtBefore(CrisisState.CRISIS.name(), now)) {
            long entries = lifecycleRepo.countEntriesSinceLastNormal(u.getId(), Instant.EPOCH);
            if (entries >= 2) {
                transition(u, CrisisState.CRISIS, CrisisState.REVIEW, "TWO_ENTRIES_FORCED_REVIEW", now);
                log.warn("user {} crisis chain reached {} entries → REVIEW (forced manual close)",
                        u.getId(), entries);
            } else {
                u.setCrisisEndsAt(now);   // COOLING 态下复用为"进入冷却时间"水位（新风险事件判据）
                transition(u, CrisisState.CRISIS, CrisisState.COOLING, "COOLDOWN_EXPIRED", now);
            }
        }
        for (UserEntity u : userRepo.findByCrisisState(CrisisState.COOLING.name())) {
            if (eligibleForRecovery(u, now)) {
                transition(u, CrisisState.COOLING, CrisisState.NORMAL, "AUTO_RECOVERED", now);
                u.setCrisisStartedAt(null);
                u.setCrisisEndsAt(null);
                userRepo.save(u);
            }
        }
    }

    /** 管理员复核结案（A2 联动，下篇·S1）：任意危机态 → NORMAL */
    @Transactional
    public void adminClose(long userId, Long adminId, Long eventId) {
        UserEntity u = userRepo.findById(userId).orElse(null);
        if (u == null) return;
        CrisisState cur = CrisisState.parse(u.getCrisisState());
        if (!cur.active()) return;
        transition(u, cur, CrisisState.NORMAL, "ADMIN_CLOSED", Instant.now());
        u.setCrisisStartedAt(null);
        u.setCrisisEndsAt(null);
        userRepo.save(u);
        audit.record(adminId, "ADMIN_CLOSE_CRISIS", "user:" + userId, null);
    }

    // ---------------- 冷却自动解除判据 ----------------

    /** 连续 downgradeWeeks 个完整自然周画像 avgValence > 阈值，且冷却开始后无新风险事件 */
    boolean eligibleForRecovery(UserEntity u, Instant now) {
        Instant coolingSince = u.getCrisisEndsAt();
        if (coolingSince == null) return false;
        List<RiskEventEntity> newEvents = riskEventRepo
                .findByUserIdAndCreatedAtAfter(u.getId(), coolingSince);
        if (!newEvents.isEmpty()) return false;

        List<String> weeks = lastCompleteWeeks(LocalDate.now(zone), downgradeWeeks);
        List<EmotionProfileEntity> profiles = profileRepo.findByUserIdAndStatWeekIn(u.getId(), weeks);
        if (profiles.size() < downgradeWeeks) return false;
        for (EmotionProfileEntity p : profiles) {
            BigDecimal v = p.getAvgValence();
            if (v == null || v.doubleValue() <= downgradeThreshold) return false;
        }
        return true;
    }

    /** 最近 N 个"已完结"的 ISO 自然周（本周一之前的整周） */
    public static List<String> lastCompleteWeeks(LocalDate today, int n) {
        WeekFields wf = WeekFields.ISO;
        LocalDate thisMonday = today.with(DayOfWeek.MONDAY);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            LocalDate probe = thisMonday.minusWeeks(i + 1L);
            out.add(probe.get(wf.weekBasedYear()) + "-W"
                    + String.format("%02d", probe.get(wf.weekOfWeekBasedYear())));
        }
        return out;
    }

    // ---------------- 内部 ----------------

    private void transition(UserEntity u, CrisisState from, CrisisState to, String reason, Instant now) {
        u.setCrisisState(to.name());
        record(u.getId(), from, to, reason, null, null);
    }

    private void record(long userId, CrisisState from, CrisisState to, String reason,
                        Long eventId, Long operatorId) {
        CrisisLifecycleEntity c = new CrisisLifecycleEntity();
        c.setUserId(userId);
        c.setFromState(from.name());
        c.setToState(to.name());
        c.setReason(reason);
        c.setEventId(eventId);
        c.setOperatorId(operatorId);
        c.setCreatedAt(Instant.now());
        lifecycleRepo.save(c);
    }
}
