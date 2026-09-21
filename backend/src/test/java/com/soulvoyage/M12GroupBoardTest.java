package com.soulvoyage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.admin.AdminBoardController;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.auth.RolePermissionEntity;
import com.soulvoyage.auth.RolePermissionRepository;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.board.GroupBoardService;
import com.soulvoyage.domain.board.SupportGroupMemberRepository;
import com.soulvoyage.domain.checkin.MoodCheckInEntity;
import com.soulvoyage.domain.checkin.MoodCheckInRepository;
import com.soulvoyage.domain.emotion.EmotionTrajectoryEntity;
import com.soulvoyage.domain.emotion.EmotionTrajectoryRepository;
import com.soulvoyage.domain.notify.PreferencesService;
import com.soulvoyage.domain.risk.RiskEventEntity;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M12 辅导员群体看板回归（§11.3 V2.x 第二项）：三道隐私闸逐条钉死——
 * 授权（无偏好行=不授权）、阈值（授权 &lt;10 人整块抑制）、小样本日（当日贡献 &lt;3 人不出均值）；
 * 外加 RBAC 表闸门、成员维护校验、载荷无个体泄露。
 */
@SpringBootTest
@ActiveProfiles("test")
class M12GroupBoardTest {

    @Autowired AdminBoardController board;
    @Autowired GroupBoardService boardService;
    @Autowired PreferencesService prefs;
    @Autowired UserRepository userRepo;
    @Autowired RolePermissionRepository permRepo;
    @Autowired MoodCheckInRepository checkIns;
    @Autowired EmotionTrajectoryRepository trajectory;
    @Autowired RiskEventRepository riskRepo;
    @Autowired SupportGroupMemberRepository membersRepo;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    private static final String PWD = "Passw0rd!2026";

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("m12" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode(PWD));
        return userRepo.save(u).getId();
    }

    private AuthPrincipal adminP;

    private AuthPrincipal adminP() {
        if (adminP == null) adminP = new AuthPrincipal(newUserAs("ADMIN"), "ADMIN");
        return adminP;
    }

    private <T> T asAdmin(Supplier<T> call) {
        var auth = new UsernamePasswordAuthenticationToken(adminP(), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private long newUserAs(String role) {
        UserEntity u = new UserEntity();
        u.setUsername(role.toLowerCase() + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode(PWD));
        u.setRole(role);
        return userRepo.save(u).getId();
    }

    private long createGroup(String name) {
        Map<String, Object> g = asAdmin(() -> board.create(adminP(), new AdminBoardController.GroupBody(name))).getData();
        return ((Number) g.get("id")).longValue();
    }

    private void consent(long uid) {
        prefs.update(uid, Map.of("counselorBoardOn", true));
    }

    private void seedCheckIn(long uid, LocalDate date, String code, int rating, int energy) {
        MoodCheckInEntity m = new MoodCheckInEntity();
        m.setUserId(uid);
        m.setCheckDate(date);
        m.setEmotionCode(code);
        m.setRating((short) rating);
        m.setEnergy((short) energy);
        m.setNoteEnc(new byte[] {1, 2, 3});   // ✦ 备注列塞入任何聚合都不该碰的数据
        checkIns.save(m);
    }

    private void seedTraj(long uid, LocalDate date, String valence) {
        EmotionTrajectoryEntity t = new EmotionTrajectoryEntity();
        t.setUserId(uid);
        t.setRecordDate(date);
        t.setSourceType("SELF_RATING");
        t.setPrimaryEmotion("平静");
        t.setValence(new BigDecimal(valence));
        t.setIntensity(new BigDecimal("0.50"));
        trajectory.save(t);
    }

    /** 造一个刚好达标的最小群体：n 个成员，前 consented 个授权并打卡 */
    private List<Long> seedGroup(long gid, int n, int consented, int rating) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add(newUser());
        asAdmin(() -> board.addMembers(adminP(), gid,
                new AdminBoardController.MembersBody(ids)));
        for (int i = 0; i < consented; i++) {
            consent(ids.get(i));
            seedCheckIn(ids.get(i), LocalDate.now(), "calm", rating, 3);
        }
        return ids;
    }

    // ---------------- 闸门 1：RBAC 权限表一票否决 ----------------

    @Test
    void permissionTableGatesEveryBoardEndpoint() {
        long userUid = newUserAs("USER");
        var userP = new AuthPrincipal(userUid, "USER");
        assertThrows(AccessDeniedException.class, () -> {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    userP, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            try {
                board.groups();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        // ADMIN 但摘掉 admin:board 权限行：照样拒——权限真源在表
        long adminUid = newUserAs("ADMIN");
        var row = permRepo.findAll().stream()
                .filter(r -> r.getRole().equals("ADMIN") && r.getPermissionCode().equals("admin:board"))
                .findFirst().orElseThrow();
        permRepo.delete(row);
        permRepo.flush();
        assertThrows(AccessDeniedException.class,
                () -> asAdminRaw(adminUid, () -> board.groups()));
        RolePermissionEntity restore = new RolePermissionEntity();
        restore.setRole("ADMIN");
        restore.setPermissionCode("admin:board");
        permRepo.save(restore);
        assertTrue(asAdminRaw(adminUid, () -> board.groups()).getData() != null);
    }

    private <T> T asAdminRaw(long uid, Supplier<T> call) {
        var auth = new UsernamePasswordAuthenticationToken(new AuthPrincipal(uid, "ADMIN"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ---------------- 闸门 2：授权是入池前提，默认关 ----------------

    @Test
    void consentDecidesInclusionAndDefaultsToOff() {
        assertFalse((Boolean) prefs.get(newUser()).get("counselorBoardOn"), "无偏好行必须视为未授权");
        long gid = createGroup("m12-consent-" + System.nanoTime());
        // 11 成员、10 授权（rating=4）+ 1 未授权（故意不打授权但打同样的卡，rating=1）
        List<Long> ids = seedGroup(gid, 11, 10, 4);
        seedCheckIn(ids.get(10), LocalDate.now(), "angry", 1, 1);

        Map<String, Object> stats = asAdmin(
                () -> board.stats(adminP(), gid, 7)).getData();
        assertEquals(false, stats.get("suppressed"));
        Map<?, ?> checkIn = (Map<?, ?>) stats.get("checkIns");
        assertEquals(10L, ((Number) checkIn.get("contributors")).longValue(), "未授权成员不得进人数");
        assertEquals(10L, ((Number) checkIn.get("personDays")).longValue());
        assertEquals(4.0, ((Number) checkIn.get("avgRating")).doubleValue(), 1e-9, "均值只由授权成员构成");
        // 情绪分布同样排除未授权者的 angry
        assertTrue(((List<?>) checkIn.get("emotions")).stream()
                .noneMatch(e -> "angry".equals(((Map<?, ?>) e).get("code"))));

        // 第 11 人转授权后立刻进池（授权即时生效，无需群体变动）
        consent(ids.get(10));
        Map<?, ?> after = (Map<?, ?>) asAdmin(() -> board.stats(adminP(), gid, 7)).getData().get("checkIns");
        assertEquals(11L, ((Number) after.get("contributors")).longValue());
    }

    // ---------------- 闸门 3：阈值抑制（授权 <10 只回结论） ----------------

    @Test
    void smallCohortIsSuppressedWithoutAnyMetrics() {
        long gid = createGroup("m12-threshold-" + System.nanoTime());
        seedGroup(gid, 12, 9, 4);   // 12 名成员、9 人授权：人数够但授权不够
        Map<String, Object> stats = asAdmin(() -> board.stats(adminP(), gid, 7)).getData();
        assertEquals(true, stats.get("suppressed"));
        assertEquals(9, ((Number) stats.get("consentedCount")).intValue());
        assertEquals(10, ((Number) stats.get("threshold")).intValue());
        assertFalse(stats.containsKey("checkIns"), "抑制态不得携带任何指标字段");
        assertFalse(stats.containsKey("valenceByDay"));
        assertFalse(stats.containsKey("riskEvents"));
        assertTrue(((String) stats.get("reason")).contains("防个体反推"));

        // 补到 10 人授权即解禁
        List<?> pending = (List<?>) asAdmin(() -> board.detail(gid)).getData().get("members");
        long toConsent = ((List<?>) pending).stream().map(m -> (Map<?, ?>) m)
                .filter(m -> Boolean.FALSE.equals(m.get("consented")))
                .findFirst().map(m -> ((Number) m.get("userId")).longValue()).orElseThrow();
        consent(toConsent);
        assertEquals(false, asAdmin(() -> board.stats(adminP(), gid, 7)).getData().get("suppressed"));
    }

    // ---------------- 闸门 4：单日小样本不出均值 ----------------

    @Test
    void dayWithTooFewContributorsIsHiddenEvenInADisentGroup() {
        long gid = createGroup("m12-daily-" + System.nanoTime());
        List<Long> ids = seedGroup(gid, 10, 10, 4);
        LocalDate today = LocalDate.now();
        // 昨日只有 1 人贡献轨迹：群体达标也整日隐藏；前日 3 人：出均值
        seedTraj(ids.get(0), today.minusDays(1), "0.900");
        seedTraj(ids.get(0), today.minusDays(2), "0.300");
        seedTraj(ids.get(1), today.minusDays(2), "0.500");
        seedTraj(ids.get(2), today.minusDays(2), "0.700");

        Map<String, Object> stats = asAdmin(() -> board.stats(adminP(), gid, 7)).getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byDay = (List<Map<String, Object>>) stats.get("valenceByDay");
        assertTrue(byDay.stream().noneMatch(d -> d.get("date").equals(today.minusDays(1).toString())),
                "单人贡献日不得出现在曲线里");
        Map<String, Object> shared = byDay.stream()
                .filter(d -> d.get("date").equals(today.minusDays(2).toString())).findFirst().orElseThrow();
        assertEquals(0.5, ((Number) shared.get("avgValence")).doubleValue(), 1e-9);
        assertTrue(((Number) stats.get("hiddenDays")).intValue() >= 1);
    }

    // ---------------- 载荷零个体 + 风险只出计数 ----------------

    @Test
    void statsPayloadNeverCarriesIndividualRows() throws Exception {
        long gid = createGroup("m12-leak-" + System.nanoTime());
        List<Long> ids = seedGroup(gid, 10, 10, 4);
        List<String> names = ids.stream().map(id -> userRepo.findById(id).orElseThrow().getUsername()).toList();
        long highUid = ids.get(0);
        RiskEventEntity r = new RiskEventEntity();
        r.setUserId(highUid);
        r.setLevel("HIGH");
        r.setTriggerType("KEYWORD_RULE");
        r.setActionTaken("CRISIS_CARD");
        r.setCreatedAt(Instant.now());
        r.setEvidenceRefEnc(new byte[] {9, 9, 9});
        riskRepo.save(r);

        String json = mapper.writeValueAsString(asAdmin(() -> board.stats(adminP(), gid, 7)).getData());
        for (String n : names) assertFalse(json.contains(n), "载荷出现个体用户名: " + n);
        assertFalse(json.contains("userId"), "载荷不应出现任何个体主键");
        Map<?, ?> risk = (Map<?, ?>) asAdmin(() -> board.stats(adminP(), gid, 7)).getData().get("riskEvents");
        assertEquals(1L, ((Number) risk.get("HIGH")).longValue(), "风险只出级别计数");
    }

    // ---------------- 成员维护边界 ----------------

    @Test
    void memberMaintenanceValidatesAndDedups() {
        long gid = createGroup("m12-member-" + System.nanoTime());
        long a = newUser(), b = newUser();
        UserEntity gone = userRepo.findById(newUser()).orElseThrow();
        gone.setDeletedAt(Instant.now());
        userRepo.save(gone);

        assertThrows(BizException.class, () -> asAdmin(
                () -> board.addMembers(adminP(), gid, new AdminBoardController.MembersBody(List.of(999999999L)))));
        assertThrows(BizException.class, () -> asAdmin(
                () -> board.addMembers(adminP(), gid, new AdminBoardController.MembersBody(List.of(gone.getId())))));

        asAdmin(() -> board.addMembers(adminP(), gid, new AdminBoardController.MembersBody(List.of(a, b, a))));
        assertEquals(2, membersRepo.countByGroupId(gid), "重复添加应幂等");
        asAdmin(() -> board.removeMember(adminP(), gid, b));
        assertEquals(1, membersRepo.countByGroupId(gid));
        assertThrows(BizException.class, () -> asAdmin(() -> board.detail(987654321L)));
    }
}
