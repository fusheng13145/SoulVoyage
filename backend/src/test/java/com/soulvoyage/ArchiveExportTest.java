package com.soulvoyage;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.archive.ArchiveService;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.orchestrator.OrchestratorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** UC5 档案导出安全回归（手册 §7.2）：限时一次性链接 + 属主断言（防跨账号领取） */
@SpringBootTest
@ActiveProfiles("test")
class ArchiveExportTest {

    @Autowired ArchiveService archive;
    @Autowired OrchestratorService orchestrator;
    @Autowired UserRepository userRepo;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("a" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("Passw0rd!2026"));
        return userRepo.save(u).getId();
    }

    @Test
    void exportLinkIsOneTimeAndOwnerBound() {
        long uid = newUser();
        long other = newUser();

        String fileId = archive.createExport(uid, "127.0.0.1");
        ObjectNode snap = archive.downloadExport(uid, fileId, "127.0.0.1");
        assertTrue(snap.has("reports"));

        // 领取即焚：同一 fileId 二次下载按不存在处理
        var e2 = assertThrows(BizException.class, () -> archive.downloadExport(uid, fileId, "127.0.0.1"));
        assertEquals(ErrorCode.NOT_FOUND.code(), e2.getErrorCode().code());

        // 属主断言：他人持链接不可领取（首次下载前已断言，验证跨用户路径）
        String fileId2 = archive.createExport(uid, "127.0.0.1");
        var e3 = assertThrows(BizException.class, () -> archive.downloadExport(other, fileId2, "127.0.0.1"));
        assertEquals(ErrorCode.FORBIDDEN.code(), e3.getErrorCode().code());
        // 越权尝试不应消耗链接：属主仍可领取
        assertNotNull(archive.downloadExport(uid, fileId2, "127.0.0.1"));

        assertThrows(BizException.class, () -> archive.downloadExport(uid, "no-such-file", null));
    }

    @Test
    void weeklySummaryAggregatesAfterDiary() throws Exception {
        long uid = newUser();
        var input = mapper.createObjectNode();
        input.put("diaryText", "小组作业全是我在做，越想越气，但我不敢提。");
        input.put("recordDate", LocalDate.now().toString());
        var t = orchestrator.submit(uid, "DIARY_PIPELINE", input, "wk-" + System.nanoTime());
        awaitFinish(t.getTaskNo());

        ObjectNode summary = archive.weeklySummary(uid, LocalDate.now());
        assertTrue(summary.path("statWeek").asText().matches("\\d{4}-W\\d{2}"));
        assertTrue(summary.path("emotionPoints").size() >= 1, "当周情绪点应进入周报");
        assertEquals("LOW", summary.path("profile").path("riskLevel").asText());
        assertTrue(summary.path("profile").path("stressorTop").size() >= 1, "压力源 Top 来自 TRACE 报告聚合");
    }

    private void awaitFinish(String taskNo) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            String s = orchestrator.byNo(taskNo).getStatus();
            if (s.equals("SUCCESS") || s.equals("FAILED") || s.equals("PARTIAL_SUCCESS")) return;
            Thread.sleep(100);
        }
        fail("任务未在 15s 内结束");
    }
}
