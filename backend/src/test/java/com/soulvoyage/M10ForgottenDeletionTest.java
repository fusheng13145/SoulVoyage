package com.soulvoyage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.account.AccountService;
import com.soulvoyage.agent.companion.CompanionService;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.auth.AuthService;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.util.Ulid;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.archive.ArchiveService;
import com.soulvoyage.domain.checkin.MoodCheckInEntity;
import com.soulvoyage.domain.checkin.MoodCheckInRepository;
import com.soulvoyage.domain.companion.CompanionTurnRepository;
import com.soulvoyage.domain.diary.DiaryEntity;
import com.soulvoyage.domain.diary.DiaryRepository;
import com.soulvoyage.domain.diary.DiaryService;
import com.soulvoyage.domain.diary.DraftEntity;
import com.soulvoyage.domain.diary.DraftRepository;
import com.soulvoyage.domain.report.ReportController;
import com.soulvoyage.domain.report.ReportEntity;
import com.soulvoyage.domain.report.ReportRepository;
import com.soulvoyage.domain.risk.RiskEventEntity;
import com.soulvoyage.domain.risk.RiskEventRepository;
import com.soulvoyage.domain.task.AgentMessageEntity;
import com.soulvoyage.domain.task.AgentMessageRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.audit.AuditLogRepository;
import com.soulvoyage.dto.AuthDtos.LoginReq;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上线门禁（下篇·十）：注销即遗忘端到端贯穿用例。
 * 一次销毁要同时钉死三件事——各业务域密文永久不可解（含最小隐私列清理）、
 * 读路径降级为占位而不泄漏不 500、留痕（审计哈希链 + 导出记录）完整且不含明文。
 */
@SpringBootTest
@ActiveProfiles("test")
class M10ForgottenDeletionTest {

    @Autowired AccountService account;
    @Autowired AuthService auth;
    @Autowired CompanionService companion;
    @Autowired ReportController reports;
    @Autowired DiaryService diaryService;
    @Autowired ArchiveService archive;
    @Autowired CryptoService crypto;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepo;
    @Autowired DiaryRepository diaryRepo;
    @Autowired DraftRepository draftRepo;
    @Autowired MoodCheckInRepository checkInRepo;
    @Autowired CompanionTurnRepository turnRepo;
    @Autowired ReportRepository reportRepo;
    @Autowired RiskEventRepository riskRepo;
    @Autowired AgentMessageRepository msgRepo;
    @Autowired AuditLogRepository auditRepo;

    @Value("${soulvoyage.account.deletion-cooldown}")
    private Duration cooldown;

    private static final String PWD = "Passw0rd!2026";
    private static final String CANARY = "这句只有我自己看得到";

    /** 各域各落一条含金丝雀的密文（companion/report 走真实写入口），返回 (域标签, 密文) */
    private List<Object[]> seedEveryDomain(long uid) throws Exception {
        List<Object[]> blobs = new ArrayList<>();

        UserEntity u = userRepo.findById(uid).orElseThrow();
        u.setPhoneEnc(crypto.encryptUserField(uid, CANARY + "|phone"));
        u.setNickname("原昵称");
        userRepo.save(u);
        blobs.add(new Object[]{"user.phoneEnc", u.getPhoneEnc()});

        DiaryEntity d = new DiaryEntity();
        d.setUserId(uid);
        d.setRecordDate(LocalDate.now());
        d.setTaskId(1L);
        d.setContentEnc(crypto.encryptUserField(uid, CANARY + "|diary"));
        blobs.add(new Object[]{"diary.contentEnc", diaryRepo.save(d).getContentEnc()});

        DraftEntity dr = new DraftEntity();
        dr.setUserId(uid);
        dr.setKind("DIARY");
        dr.setContentEnc(crypto.encryptUserField(uid, CANARY + "|draft"));
        blobs.add(new Object[]{"draft.contentEnc", draftRepo.save(dr).getContentEnc()});

        MoodCheckInEntity ci = new MoodCheckInEntity();
        ci.setUserId(uid);
        ci.setCheckDate(LocalDate.now());
        ci.setEmotionCode("LOW");
        ci.setNoteEnc(crypto.encryptUserField(uid, CANARY + "|checkin"));
        blobs.add(new Object[]{"checkIn.noteEnc", checkInRepo.save(ci).getNoteEnc()});

        long sid = companion.openOrReuse(uid).sessionId();
        long turnId = companion.turn(uid, sid, CANARY + "，今天想把事情理顺一下。").turnId();
        blobs.add(new Object[]{"companion.userTextEnc",
                turnRepo.findById(turnId).orElseThrow().getUserTextEnc()});

        ReportEntity r = new ReportEntity();
        r.setUserId(uid);
        r.setType("TRACE");
        r.setTaskId(1L);
        r.setTitle("贯穿用例报告");
        r.setContentEnc(crypto.encryptUserField(uid, CANARY + "|report"));
        r = reportRepo.save(r);
        blobs.add(new Object[]{"report.contentEnc", r.getContentEnc()});
        reports.feedback(new AuthPrincipal(uid, "USER"), r.getId(),
                new ReportController.FeedbackReq("USEFUL", CANARY + "|feedbackNote"));
        blobs.add(new Object[]{"report.feedbackNoteEnc",
                reportRepo.findById(r.getId()).orElseThrow().getFeedbackNoteEnc()});

        ObjectNode ev = mapper.createObjectNode().put("ruleCode", "RISK_CRISIS").put("note", CANARY);
        RiskEventEntity e = new RiskEventEntity();
        e.setUserId(uid);
        e.setLevel("MEDIUM");
        e.setTriggerType("KEYWORD_RULE");
        e.setRuleCode("RISK_CRISIS");
        e.setEvidenceRefEnc(crypto.encryptUserField(uid, ev.toString()));
        e.setActionTaken("PROFILE_FLAG");
        e.setNeedsReview((short) 1);
        blobs.add(new Object[]{"risk.evidenceRefEnc", riskRepo.save(e).getEvidenceRefEnc()});

        AgentMessageEntity m = new AgentMessageEntity();
        m.setMessageNo(Ulid.next());
        m.setTaskId(1L);
        m.setStepSeq(1);
        m.setFromAgent("ORCHESTRATOR");
        m.setToAgent("EMOTION");
        m.setMsgType("REQUEST");
        m.setPayloadEnc(crypto.encryptUserField(uid, CANARY + "|agentMessage"));
        blobs.add(new Object[]{"agentMessage.payloadEnc", msgRepo.save(m).getPayloadEnc()});

        for (Object[] b : blobs) {
            assertNotNull(b[1], "种子密文缺失: " + b[0]);
            assertTrue(crypto.decryptUserField(uid, (byte[]) b[1]).contains(CANARY),
                    "销毁前应可读: " + b[0]);
        }
        return blobs;
    }

    @Test
    void deletionDestroysEveryDomainAndDegradesReadPaths() throws Exception {
        UserEntity fresh = new UserEntity();
        fresh.setUsername("fd" + System.nanoTime());
        fresh.setPasswordHash(encoder.encode(PWD));
        fresh.setStatus((short) 1);
        long uid = userRepo.save(fresh).getId();
        String username = fresh.getUsername();
        List<Object[]> blobs = seedEveryDomain(uid);

        // ① 申请：口令二次确认 → status=3，冷静期内可登录（只为撤回），重复申请被拒
        assertThrows(BizException.class, () -> account.requestDeletion(uid, "wrong-pass", "127.0.0.1"));
        assertEquals((short) 1, userRepo.findById(uid).orElseThrow().getStatus());
        account.requestDeletion(uid, PWD, "127.0.0.1");
        assertEquals((short) 3, userRepo.findById(uid).orElseThrow().getStatus());
        assertThrows(BizException.class, () -> account.requestDeletion(uid, PWD, "127.0.0.1"));
        assertTrue(auth.login(new LoginReq(username, PWD), "127.0.0.1").deletionPending());

        // ② 冷静期到期 → 密钥销毁式注销
        UserEntity pending = userRepo.findById(uid).orElseThrow();
        pending.setDeletionRequestedAt(Instant.now().minus(cooldown).minus(Duration.ofDays(1)));
        userRepo.save(pending);
        assertTrue(account.processExpiredDeletions() >= 1, "到期申请必须被执行");

        // ③ 各域密文永久不可解，且不再有 ACTIVE 密钥
        for (Object[] b : blobs) {
            byte[] blob = (byte[]) b[1];
            String label = (String) b[0];
            assertThrows(BizException.class, () -> crypto.decryptUserField(uid, blob),
                    "密钥已销毁仍可读: " + label);
        }
        assertEquals(0, crypto.activeKeyVersion(uid), "不得残留 ACTIVE 密钥");

        // ④ 最小隐私列清理 + 执行后不可撤回
        UserEntity dead = userRepo.findById(uid).orElseThrow();
        assertEquals("已注销用户", dead.getNickname());
        assertNull(dead.getPhoneEnc(), "手机号密文一并清除");
        assertNotNull(dead.getDeletedAt());
        assertThrows(BizException.class, () -> account.cancelDeletion(uid, null), "执行后不可撤回");
        assertThrows(BizException.class, () -> auth.login(new LoginReq(username, PWD), "127.0.0.1"),
                "注销执行的账号不能再登录");

        // ⑤ 读路径降级：占位而非明文，且不整页 500
        Map<String, Object> all = diaryService.list(uid, null, null, null, 0, 20);
        assertEquals(1, all.get("total"));
        assertEquals("（无法解密）",
                ((Map<?, ?>) ((List<?>) all.get("items")).get(0)).get("preview"));
        assertTrue(((List<?>) diaryService.list(uid, null, null, CANARY, 0, 20).get("items")).isEmpty(),
                "搜索不得命中明文");
        JsonNode snapshot = archive.downloadExport(uid, archive.createExport(uid, "127.0.0.1"), "127.0.0.1");
        String dump = snapshot.toString();
        assertFalse(dump.contains(CANARY), "导出快照不得含明文");
        assertTrue(dump.contains("无法解密"), "导出快照要说明为何是占位");

        // ⑥ 留痕完整且不含明文
        for (String action : List.of("DELETE_REQUEST", "DELETE_EXECUTED")) {
            assertEquals(1L, auditRepo.findForAdmin(uid, action, PageRequest.of(0, 5)).getTotalElements(),
                    "注销留痕缺失: " + action);
        }
        assertTrue(auditRepo.findForAdmin(uid, null, PageRequest.of(0, 50)).getContent().stream()
                .allMatch(a -> a.getTarget() == null || !a.getTarget().contains(CANARY)));
    }

    @Test
    void auditHashChainStaysIntactAcrossDeletion() {
        String prev = "0".repeat(64);
        for (var a : auditRepo.findAllByOrderByIdAsc()) {
            String expect = sha256(prev + "|" + a.getUserId() + "|" + a.getAction()
                    + "|" + a.getTarget() + "|" + a.getIp());
            assertEquals(expect, a.getHashChain(), "审计链在 id=" + a.getId() + " 处断裂");
            prev = expect;
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
