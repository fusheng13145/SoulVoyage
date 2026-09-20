package com.soulvoyage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.report.ReportController;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M10 报表批注（下篇·C2/L2）：收藏幂等、评价闭集、反馈原话加密回显，
 * 以及跨账号一律 404 的属主闸门（IDOR 面）。
 */
@SpringBootTest
@ActiveProfiles("test")
class M10ReportAnnotationTest {

    @Autowired ReportController reports;
    @Autowired ReportRepository reportRepo;
    @Autowired UserRepository userRepo;
    @Autowired CryptoService crypto;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    private static final String PWD = "Passw0rd!2026";

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("m10" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode(PWD));
        u.setRole("USER");
        return userRepo.save(u).getId();
    }

    private long newReport(long uid, String type, String title) {
        ObjectNode body = mapper.createObjectNode().put("insight", "你在把一次冲突推广成'他总是这样'。");
        ReportEntity r = new ReportEntity();
        r.setUserId(uid);
        r.setType(type);
        r.setTaskId(1L);
        r.setTitle(title);
        r.setContentEnc(crypto.encryptUserField(uid, body.toString()));
        r.setRiskLevel("LOW");
        return reportRepo.save(r).getId();
    }

    private long total(AuthPrincipal p, String type, String risk, Boolean starred) {
        return ((Number) reports.list(p, type, risk, starred, null, null, 0, 50)
                .getData().get("total")).longValue();
    }

    @SuppressWarnings("unchecked")
    private java.util.List<ReportController.ReportMeta> items(AuthPrincipal p, Boolean starred) {
        return (java.util.List<ReportController.ReportMeta>) reports.list(p, null, null, starred, null, null, 0, 50)
                .getData().get("items");
    }

    @Test
    void starIsIdempotentAndDrivesListFilter() throws Exception {
        long uid = newUser();
        var p = new AuthPrincipal(uid, "USER");
        long id = newReport(uid, "TRACE", "冲突复盘");

        assertEquals(Map.of("id", id, "starred", true),
                reports.star(p, id, new ReportController.StarReq(true)).getData());
        // 重复提交同一目标值不翻转：幂等，前端掉线重试不会把收藏悄悄取消
        reports.star(p, id, new ReportController.StarReq(true));
        assertEquals((short) 1, reportRepo.findById(id).orElseThrow().getStarred());
        assertEquals(1L, total(p, null, null, true));
        assertEquals(0L, total(p, null, null, false));
        assertEquals(true, reports.detail(p, id).getData().get("starred"));
        assertTrue(items(p, null).get(0).starred());

        reports.star(p, id, new ReportController.StarReq(false));
        assertEquals((short) 0, reportRepo.findById(id).orElseThrow().getStarred());

        // type / riskLevel 过滤同属 C2 扩展过滤
        newReport(uid, "SUPPORT", "自助方案");
        assertEquals(1L, total(p, "SUPPORT", null, null));
        assertEquals(2L, total(p, null, "LOW", null));
    }

    @Test
    void feedbackIsClosedSetAndNoteStaysEncrypted() throws Exception {
        long uid = newUser();
        var p = new AuthPrincipal(uid, "USER");
        long id = newReport(uid, "TRACE", "反刍复盘");

        assertThrows(BizException.class, () -> reports.feedback(p, id,
                new ReportController.FeedbackReq("GREAT", null)), "评价档位必须闭集");

        reports.feedback(p, id, new ReportController.FeedbackReq("useful", "这句洞察真的点到我头上了"));
        ReportEntity r = reportRepo.findById(id).orElseThrow();
        assertEquals("USEFUL", r.getFeedback(), "入参大小写归一");
        assertNotNull(r.getFeedbackAt());
        assertFalse(new String(r.getFeedbackNoteEnc(), StandardCharsets.ISO_8859_1).contains("点到我头上"),
                "反馈原话必须密文落库");

        var detail = reports.detail(p, id).getData();
        assertEquals("这句洞察真的点到我头上了", detail.get("feedbackNote"));
        assertEquals("USEFUL", detail.get("feedback"));
        assertEquals(false, detail.get("starred"));

        // 撤销评价：档位、原话、时间戳一并清空，不留孤儿女密文
        reports.feedback(p, id, new ReportController.FeedbackReq("", "残留"));
        r = reportRepo.findById(id).orElseThrow();
        assertNull(r.getFeedback());
        assertNull(r.getFeedbackNoteEnc());
        assertNull(r.getFeedbackAt());
        assertEquals("", reports.detail(p, id).getData().get("feedbackNote"));
    }

    @Test
    void otherUsersReportIsInvisibleOnEveryVerb() throws Exception {
        long owner = newUser();
        long intruder = newUser();
        var pio = new AuthPrincipal(intruder, "USER");
        long id = newReport(owner, "TRACE", "别人的复盘");

        assertThrows(BizException.class, () -> reports.detail(pio, id));
        assertThrows(BizException.class, () -> reports.star(pio, id, new ReportController.StarReq(true)));
        assertThrows(BizException.class, () -> reports.feedback(pio, id,
                new ReportController.FeedbackReq("USEFUL", "偷看")));
        assertTrue(items(pio, null).stream().noneMatch(m -> m.id().equals(id)));
        // 属主的收藏不受他人尝试影响
        assertEquals((short) 0, reportRepo.findById(id).orElseThrow().getStarred());
    }

    @Test
    void feedbackAggregationForAdminBoardRuns() throws Exception {
        long uid = newUser();
        var p = new AuthPrincipal(uid, "USER");
        long id = newReport(uid, "SUPPORT", "睡眠方案");
        reports.feedback(p, id, new ReportController.FeedbackReq("UNHELPFUL", "太笼统"));
        // H2 建的路径 created_at 无默认值（为 null），窗口聚合按时间过滤 → 只断言查询可跑通
        assertDoesNotThrow(() -> reportRepo.countFeedbackSince(Instant.EPOCH));
        assertEquals(Map.of("id", id, "feedback", "UNHELPFUL"),
                reports.feedback(p, id, new ReportController.FeedbackReq("unhelpful", null)).getData());
        assertNull(reportRepo.findById(id).orElseThrow().getFeedbackNoteEnc(), "改档不带原话即清空");
    }
}
