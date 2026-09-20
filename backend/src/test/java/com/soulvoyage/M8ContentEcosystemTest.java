package com.soulvoyage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.soulvoyage.admin.ContentAdminController;
import com.soulvoyage.agent.simulate.SimulateReviewAgent;
import com.soulvoyage.agent.simulate.SimulationController;
import com.soulvoyage.agent.simulate.SimulationService;
import com.soulvoyage.auth.AuthPrincipal;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.domain.content.ContentStore;
import com.soulvoyage.domain.content.KgNodeRepository;
import com.soulvoyage.domain.content.ReadingController;
import com.soulvoyage.domain.content.ReadingService;
import com.soulvoyage.domain.profile.EmotionProfileEntity;
import com.soulvoyage.domain.profile.EmotionProfileRepository;
import com.soulvoyage.domain.simulate.SimulateSessionEntity;
import com.soulvoyage.domain.simulate.SimulateSessionRepository;
import com.soulvoyage.domain.task.TaskInstanceEntity;
import com.soulvoyage.domain.task.TaskInstanceRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.kg.KgSearchService;
import com.soulvoyage.orchestrator.OrchestratorService;
import com.soulvoyage.orchestrator.api.TaskController;
import com.soulvoyage.orchestrator.agent.OutputInvalidException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M8 内容与训练生态回归（N1–N4 / C3 / C5）：
 * 种子扩容、管理端热更新端到端、每日一读纯规则供给、复盘案例闭集校验、训练档案续练、任务历史分页。
 */
@SpringBootTest
@ActiveProfiles("test")
class M8ContentEcosystemTest {

    @Autowired ContentStore store;
    @Autowired ContentAdminController admin;
    @Autowired KgNodeRepository kgRepo;
    @Autowired ReadingService reading;
    @Autowired ReadingController readingCtrl;
    @Autowired com.soulvoyage.domain.content.UserReadLogRepository readRepo;
    @Autowired SimulationService simulation;
    @Autowired SimulationController simulationCtrl;
    @Autowired SimulateSessionRepository sessionRepo;
    @Autowired TaskController tasks;
    @Autowired TaskInstanceRepository taskRepo;
    @Autowired OrchestratorService orchestrator;
    @Autowired EmotionProfileRepository profileRepo;
    @Autowired UserRepository userRepo;
    @Autowired CryptoService crypto;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("m8" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("Passw0rd!2026"));
        return userRepo.save(u).getId();
    }

    // ---------------- N2/N1 种子扩容 ----------------

    @Test
    void seedsLoadedIntoDbBackedStore() {
        assertEquals(30, store.distortions().size(), "认知误区应扩容到 30");
        assertEquals(20, store.psyTopics().size(), "科普池应为 20 篇");
        assertEquals(15, store.commCases().size(), "沟通案例应为 15 个");
        assertEquals(12, store.techniques().size(), "优势技术应为 12 个");

        var scenes = store.scenes();
        assertEquals(10, scenes.size(), "场景卡应扩充到 10");
        for (var s : scenes) {
            assertFalse(s.tags().isEmpty(), s.code() + " 应有压力源标签");
            assertFalse(s.recommendedFor().isEmpty(), s.code() + " 应有画像推荐标签");
        }
        assertTrue(scenes.stream().anyMatch(s -> s.code().equals("TEACHER_EXTENSION")));
        assertTrue(scenes.stream().anyMatch(s -> s.code().equals("SELF_CARE")));
    }

    @Test
    void scenesApiCarriesTagsForFilteringAndRecommendation() {
        var data = simulationCtrl.listScenes().getData();
        assertEquals(10, data.size());
        for (var m : data) {
            assertNotNull(m.get("tags"));
            assertNotNull(m.get("recommendedFor"));
        }
    }

    // ---------------- N4 热更新端到端 ----------------

    @Test
    void adminUpsertTakesEffectImmediatelyWithVersionBump() {
        long uid = newUser();
        var adminPrincipal = new AuthPrincipal(uid, "ADMIN");
        long v0 = store.bump();

        String code = "pt_m8_" + System.nanoTime();
        ObjectNode payload = mapper.createObjectNode()
                .put("summary", "M8 热更新验证用摘要")
                .put("microAction", "读完后写下标题里的关键词")
                .put("readingSec", 40);
        payload.set("aboutTags", mapper.createArrayNode().add("其他"));
        var r1 = admin.upsertKg(adminPrincipal, code,
                new ContentAdminController.KgBody("PSY_TOPIC", "热更新试验篇", payload, (short) 1));
        assertEquals(0, r1.getCode());
        assertEquals(v0 + 1, ((Number) r1.getData().get("contentVersion")).longValue());
        assertTrue(r1.getData().get("effective") instanceof Boolean b && b);

        // 写成功即生效：同 JVM 快照立刻含新篇
        assertTrue(store.psyTopics().stream().anyMatch(p -> p.kgNodeId().equals(code)), "新增篇目应立即可见");
        assertTrue(kgRepo.findByCode(code).isPresent());

        // 下架：读池立刻收敛
        admin.upsertKg(adminPrincipal, code,
                new ContentAdminController.KgBody(null, null, null, (short) 0));
        assertTrue(store.psyTopics().stream().noneMatch(p -> p.kgNodeId().equals(code)), "下架后不应在池");

        // 场景卡 upsert 同样即时生效
        String sceneCode = "M8_HOT_SCENE";
        admin.upsertScene(adminPrincipal, sceneCode, new ContentAdminController.SceneBody(
                "热更新新场景", "管理端写入的场景描述", List.of("日常相处"), "热更NPC", "同学",
                mapper.createObjectNode().put("motivation", "m").put("bottomLine", "b")
                        .put("triggers", "t").put("style", "s"),
                List.of("LISTEN"), 8,
                mapper.createObjectNode().put("NORMAL", "（热更新开场白）你先说。"),
                List.of("人际冲突"), List.of("学业压力"), (short) 1));
        var added = store.scenes().stream().filter(s -> s.code().equals(sceneCode)).findFirst();
        assertTrue(added.isPresent(), "新场景应立即出现在快照");
        assertEquals(List.of("人际冲突"), added.get().tags());
        assertEquals("热更NPC", added.get().npcName());
    }

    // ---------------- N3 每日一读 ----------------

    @Test
    void dailyReadingSameCardAllDayWithFavoriteToggle() {
        long uid = newUser();
        var p = new AuthPrincipal(uid, "USER");

        var first = readingCtrl.today(p).getData();
        assertEquals(0, readingCtrl.today(p).getCode());
        String kgNodeId = String.valueOf(first.get("kgNodeId"));
        assertTrue(kgNodeId.startsWith("psy_"));
        assertFalse(String.valueOf(first.get("title")).isBlank());
        assertEquals(true, first.get("firstToday"));
        assertEquals(false, first.get("favorited"));

        var second = readingCtrl.today(p).getData();
        assertEquals(kgNodeId, second.get("kgNodeId"), "同日重复请求必须返回同一篇");
        assertEquals(false, second.get("firstToday"));

        // 收藏开关 → 卡片与列表同步
        var on = readingCtrl.toggleFavorite(p,
                new ReadingController.FavoriteReq(ReadingService.FAV_PSY, kgNodeId)).getData();
        assertEquals(true, on.get("favorited"));
        assertTrue((Boolean) readingCtrl.today(p).getData().get("favorited"));
        var favs = readingCtrl.favorites(p).getData();
        assertEquals(1, favs.size());
        assertEquals(kgNodeId, favs.get(0).get("refCode"));
        assertEquals(first.get("title"), favs.get(0).get("title"));

        assertEquals(1, reading.favoritesForExport(uid).size(), "档案导出应含收藏");
        var off = readingCtrl.toggleFavorite(p,
                new ReadingController.FavoriteReq(ReadingService.FAV_PSY, kgNodeId)).getData();
        assertEquals(false, off.get("favorited"));
        assertTrue(readingCtrl.favorites(p).getData().isEmpty());
    }

    @Test
    void dailyReadingDeduplicatesHistoryThenRotates() {
        long uid = newUser();
        var pool = store.psyTopics();
        var target = pool.get(3);
        // 历史已读全部记在昨天：今日无同日记录，去重靠跨日 seen
        for (var e : pool) {
            if (e == target) continue;
            com.soulvoyage.domain.content.UserReadLogEntity log =
                    new com.soulvoyage.domain.content.UserReadLogEntity();
            log.setUserId(uid);
            log.setCode(e.kgNodeId());
            log.setReadDate(java.time.LocalDate.now().minusDays(1));
            readRepo.save(log);
        }
        assertEquals(target.kgNodeId(), reading.today(uid).get("kgNodeId"),
                "应跳过全部历史已读，发仅剩未读的一篇");

        // 整池读完：重新轮换，仍按规则取 code 最小
        for (var e : pool) {
            if (e.kgNodeId().equals(target.kgNodeId())) continue;   // target 已由上一步今日发放
            com.soulvoyage.domain.content.UserReadLogEntity log =
                    new com.soulvoyage.domain.content.UserReadLogEntity();
            log.setUserId(uid);
            log.setCode(e.kgNodeId());
            log.setReadDate(java.time.LocalDate.now());
            readRepo.save(log);
        }
        // 整池读完：把 target 的今日发放也挪到昨天，再请求应触发轮换重发
        readRepo.findByUserIdAndReadDate(uid, java.time.LocalDate.now())
                .forEach(l -> { l.setReadDate(java.time.LocalDate.now().minusDays(1)); readRepo.save(l); });
        String rotated = (String) reading.today(uid).get("kgNodeId");
        assertTrue(pool.stream().anyMatch(p -> p.kgNodeId().equals(rotated)));
        assertEquals(pool.stream().min(java.util.Comparator.comparing(ContentStore.PsyEntry::kgNodeId))
                .orElseThrow().kgNodeId(), rotated, "轮换后仍按同分取 code 最小");
    }

    @Test
    void dailyReadingPrefersProfileRelevance() {
        long uid = newUser();
        EmotionProfileEntity w = new EmotionProfileEntity();
        w.setUserId(uid);
        w.setStatWeek("2026-W37");
        w.setStressorTopJson("[{\"name\":\"学业压力\",\"count\":4}]");
        profileRepo.save(w);

        // 与 ReadingService 同规则推演期望值：相关性最高、同分取 code 最小
        List<String> stressors = List.of("学业压力");
        var expected = store.psyTopics().stream()
                .max(java.util.Comparator.comparingInt((ContentStore.PsyEntry e) ->
                                e.aboutTags().contains("学业压力") ? 2 : 0)
                        .thenComparing(ContentStore.PsyEntry::kgNodeId, java.util.Comparator.reverseOrder()))
                .orElseThrow();
        assertEquals(expected.kgNodeId(), reading.today(uid).get("kgNodeId"), "画像压力源应优先命中");
    }

    @Test
    void favoriteRejectsUnknownTypeAndMissingRef() {
        long uid = newUser();
        assertThrows(BizException.class, () -> reading.toggleFavorite(uid, "DIARY", "xx"));
        assertThrows(BizException.class, () -> reading.toggleFavorite(uid, ReadingService.FAV_PSY, " "));
        assertThrows(BizException.class,
                () -> reading.toggleFavorite(uid, ReadingService.FAV_PSY, "pt_not_exist"));
        assertTrue(reading.favorites(uid).isEmpty());
    }

    // ---------------- N2 复盘案例闭集校验 ----------------

    @Test
    void reviewReferenceCaseClosedSetCheck() throws Exception {
        var cases = List.of(
                new KgSearchService.CommCaseCard("cc_alpha", "案例A", "宿舍", "s", "u", "h", List.of(), List.of()),
                new KgSearchService.CommCaseCard("cc_beta", "案例B", "小组", "s", "u", "h", List.of(), List.of()));

        // 空引用放过（模型没把握可不输出）
        SimulateReviewAgent.assertCaseRef(mapper.readTree("{\"referenceCase\":\"\"}"), cases);
        SimulateReviewAgent.assertCaseRef(mapper.readTree("{}"), cases);
        // 合法引用放过
        SimulateReviewAgent.assertCaseRef(mapper.readTree("{\"referenceCase\":\"cc_beta\"}"), cases);
        // 越界引用：视为编造案例，整步作废
        assertThrows(OutputInvalidException.class, () -> SimulateReviewAgent.assertCaseRef(
                mapper.readTree("{\"referenceCase\":\"cc_invented\"}"), cases));
    }

    // ---------------- C3 训练档案与续练 ----------------

    @Test
    void interruptedSessionResumesAndLists() {
        long uid = newUser();
        var opened = simulation.open(uid, "DORM_CONFLICT", "NORMAL");
        simulation.turn(uid, opened.simulateId(), "我想跟你商量一下熄灯后的安排");

        assertEquals("INTERRUPTED", simulation.interrupt(uid, opened.simulateId()));
        var interrupted = simulation.list(uid, "INTERRUPTED", 0, 10);
        assertEquals(1, ((Number) interrupted.get("total")).intValue(), "中断列表应能单独捞出没练完的会话");
        var item = (Map<?, ?>) ((List<?>) interrupted.get("items")).get(0);
        assertEquals("DORM_CONFLICT", item.get("sceneCode"));
        assertEquals(1, ((Number) item.get("totalTurns")).intValue());
        assertNull(item.get("reportId"));

        var back = simulation.turn(uid, opened.simulateId(), "上次说到一半,今天继续");
        assertEquals(2, back.turnNo());
        assertEquals("RUNNING", sessionRepo.findById(opened.simulateId()).orElseThrow().getStatus());

        // 危机态不可续练
        var o2 = simulation.open(uid, "GROUP_PROJECT", "MILD");
        simulation.turn(uid, o2.simulateId(), "吵这些没意义，我真的不想活了。");
        assertEquals("ABORTED_RISK", simulation.interrupt(uid, o2.simulateId()), "非 RUNNING 会话 interrupt 不改变状态");
    }

    @Test
    void statsAggregatePerSceneWithRadarAndCoachMemory() throws Exception {
        long uid = newUser();
        var d1 = simulation.open(uid, "DORM_CONFLICT", "NORMAL");
        simulation.turn(uid, d1.simulateId(), "把熄灯时间定在十二点半怎么样");
        var s1 = sessionRepo.findById(d1.simulateId()).orElseThrow();
        s1.setAvgScore(new java.math.BigDecimal("83.5"));
        s1.setDimensionScores("{\"LISTEN\":92.0,\"BOUNDARY\":75.0}");   // 较早一次归档
        sessionRepo.save(s1);

        var d2 = simulation.open(uid, "DORM_CONFLICT", "NORMAL");
        simulation.turn(uid, d2.simulateId(), "好的那我们说定");
        var s2 = sessionRepo.findById(d2.simulateId()).orElseThrow();
        s2.setAvgScore(new java.math.BigDecimal("60.0"));
        s2.setDimensionScores("{\"LISTEN\":60.0,\"BOUNDARY\":55.0}");   // 最新一次（id 更大）：雷达应取这组
        s2.setWeaknessesEnc(crypto.encryptUserField(uid, "[\"请求多为模糊提议，缺少可执行的时间与数字\"]"));
        sessionRepo.save(s2);

        var stats = simulation.stats(uid);
        var dorm = stats.stream().filter(m -> m.get("sceneCode").equals("DORM_CONFLICT")).findFirst().orElseThrow();
        assertEquals(2, ((Number) dorm.get("times")).intValue());
        assertEquals(83.5, ((Number) dorm.get("bestScore")).doubleValue(), 0.001);
        assertEquals(71.8, ((Number) dorm.get("avgScore")).doubleValue(), 0.001, "均分保留一位小数");
        @SuppressWarnings("unchecked")
        Map<String, Object> radar = (Map<String, Object>) dorm.get("lastDimensions");
        assertEquals(60.0, ((Number) radar.get("LISTEN")).doubleValue(), 0.001, "雷达取最新一次归档（非更早的 92）");
        assertEquals(55.0, ((Number) radar.get("BOUNDARY")).doubleValue(), 0.001);

        // 教练记忆：同场景再有弱项留档时，新会话开场注入加密导演快照
        var d3 = simulation.open(uid, "DORM_CONFLICT", "NORMAL");
        var snap = mapper.readTree(crypto.decryptUserField(uid,
                sessionRepo.findById(d3.simulateId()).orElseThrow().getNpcStateSnapEnc()));
        assertTrue(snap.path("coachMemory").asText().contains("模糊提议"), "弱项应写入快照");

        // 无历史场景：快照不含教练记忆，逐轮按首次训练话术处理
        var d4 = simulation.open(uid, "SELF_CARE", "MILD");
        var snap4 = mapper.readTree(crypto.decryptUserField(uid,
                sessionRepo.findById(d4.simulateId()).orElseThrow().getNpcStateSnapEnc()));
        assertFalse(snap4.has("coachMemory"));
    }

    // ---------------- C5 任务历史 ----------------

    @Test
    void taskListPagesAndFilters() {
        long uid = newUser();
        var p = new AuthPrincipal(uid, "USER");
        for (int i = 0; i < 3; i++) {
            taskRepo.save(task(uid, "DIARY_PIPELINE", "SUCCESS"));
        }
        taskRepo.save(task(uid, "SIMULATE_PIPELINE", "FAILED"));
        taskRepo.save(task(newUser(), "DIARY_PIPELINE", "SUCCESS"));   // 他人任务不得混入

        var all = tasks.list(p, null, null, 0, 10).getData();
        assertEquals(4, ((Number) all.get("total")).intValue());
        assertEquals(4, ((List<?>) all.get("items")).size());
        var first = (Map<?, ?>) ((List<?>) all.get("items")).get(0);
        assertNotNull(first.get("taskNo"));
        assertNotNull(first.get("pipelineCode"));
        assertEquals("", first.get("finishedAt"), "未结束任务 finishedAt 为空串");

        var failed = tasks.list(p, "SIMULATE_PIPELINE", "FAILED", 0, 10).getData();
        assertEquals(1, ((Number) failed.get("total")).intValue());

        var page2 = tasks.list(p, "", "", 1, 3).getData();   // 空串过滤视作不过滤
        assertEquals(4, ((Number) page2.get("total")).intValue());
        assertEquals(1, ((List<?>) page2.get("items")).size());
    }

    private TaskInstanceEntity task(long uid, String pipeline, String status) {
        TaskInstanceEntity t = new TaskInstanceEntity();
        t.setTaskNo(com.soulvoyage.common.util.Ulid.next());
        t.setUserId(uid);
        t.setPipelineCode(pipeline);
        t.setStatus(status);
        return t;
    }
}
