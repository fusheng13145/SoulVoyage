package com.soulvoyage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.diary.DiaryService;
import com.soulvoyage.domain.task.TaskInstanceEntity;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.orchestrator.OrchestratorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 下篇·C1 日记本回归：落库→回看→编辑 stale→重新分析→删除→草稿；幂等重放不重复写 */
@SpringBootTest
@ActiveProfiles("test")
class DiaryBookTest {

    @Autowired DiaryService diary;
    @Autowired OrchestratorService orchestrator;
    @Autowired UserRepository userRepo;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("db" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("Passw0rd!2026"));
        return userRepo.save(u).getId();
    }

    private ObjectNode input(String text) {
        var n = mapper.createObjectNode();
        n.put("diaryText", text);
        n.put("recordDate", LocalDate.now().toString());
        n.put("moodSelfRating", 4);
        return n;
    }

    @Test
    void fullFlowListDetailEditStaleReanalyzeDelete() throws Exception {
        long uid = newUser();
        String text = "今天答辩很顺利，导师夸了我的数据分析部分，很开心！";
        var t = orchestrator.submit(uid, "DIARY_PIPELINE", input(text), "db1-" + System.nanoTime());
        diary.attachTask(uid, input(text), t);
        awaitFinish(t.getTaskNo());
        assertEquals("SUCCESS", orchestrator.byNo(t.getTaskNo()).getStatus());

        // 列表 + q 解密过滤
        var page = diary.list(uid, null, null, null, 0, 20);
        List<?> items = (List<?>) page.get("items");
        assertEquals(1, items.size());
        assertEquals(1, ((List<?>) diary.list(uid, null, null, "答辩", 0, 20).get("items")).size());
        assertEquals(0, ((List<?>) diary.list(uid, null, null, "完全不相干的词", 0, 20).get("items")).size());
        long id = ((Number) ((Map<?, ?>) items.get(0)).get("id")).longValue();

        // 详情：原文 + 报告元数据（未 stale）
        Map<String, Object> d = diary.detail(uid, id);
        assertEquals(text, d.get("content"));
        List<Map<String, Object>> reports = (List<Map<String, Object>>) d.get("reports");
        assertFalse(reports.isEmpty(), "分析完成后详情应挂到报告");
        assertTrue(reports.stream().allMatch(r -> ((Integer) r.get("stale")) == 0));
        assertFalse(((List<?>) d.get("emotions")).isEmpty(), "详情应带当日情绪标签");

        // 编辑 → 关联报告 stale
        String edited = text + "补充：其实晚上还是有点担心盲审。";
        diary.update(uid, id, edited, (short) 3);
        Map<String, Object> d2 = diary.detail(uid, id);
        assertEquals(edited, d2.get("content"));
        assertTrue(((List<Map<String, Object>>) d2.get("reports")).stream()
                .allMatch(r -> ((Integer) r.get("stale")) == 1), "编辑后旧报告应标 stale");

        // 重新分析：挂到新任务，新报告不 stale
        String taskNo = diary.reanalyze(uid, id);
        awaitFinish(taskNo);
        assertEquals("SUCCESS", orchestrator.byNo(taskNo).getStatus());
        Map<String, Object> d3 = diary.detail(uid, id);
        List<Map<String, Object>> newReports = (List<Map<String, Object>>) d3.get("reports");
        assertFalse(newReports.isEmpty());
        assertTrue(newReports.stream().allMatch(r -> ((Integer) r.get("stale")) == 0));

        // 删除：列表消失、详情 404
        diary.delete(uid, id);
        assertEquals(0, ((List<?>) diary.list(uid, null, null, null, 0, 20).get("items")).size());
        assertThrows(BizException.class, () -> diary.detail(uid, id));
    }

    @Test
    void attachIsIdempotentOnResubmit() {
        long uid = newUser();
        String text = "实验失败了三次，有点挫败，但导师说过程也算数。";
        var in = input(text);
        String reqId = "db2-" + System.nanoTime();
        TaskInstanceEntity t1 = orchestrator.submit(uid, "DIARY_PIPELINE", in, reqId);
        diary.attachTask(uid, in, t1);
        // clientReqId 重放：返回同一任务，attach 再跑一次不新增日记
        TaskInstanceEntity t2 = orchestrator.submit(uid, "DIARY_PIPELINE", in, reqId);
        diary.attachTask(uid, in, t2);
        assertEquals(t1.getTaskNo(), t2.getTaskNo());
        assertEquals(1, ((List<?>) diary.list(uid, null, null, null, 0, 20).get("items")).size());
    }

    @Test
    void draftRoundTrip() {
        long uid = newUser();
        assertEquals("", diary.getDraft(uid));
        diary.saveDraft(uid, "写到一半的话……");
        assertEquals("写到一半的话……", diary.getDraft(uid));
        diary.saveDraft(uid, "改成这些");           // upsert 单行
        assertEquals("改成这些", diary.getDraft(uid));
        diary.clearDraft(uid);
        assertEquals("", diary.getDraft(uid));
    }

    private void awaitFinish(String taskNo) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            String s = orchestrator.byNo(taskNo).getStatus();
            if (s.equals("SUCCESS") || s.equals("FAILED") || s.equals("PARTIAL_SUCCESS")) return;
            Thread.sleep(100);
        }
        fail("任务未在 15s 内结束: " + taskNo);
    }
}
