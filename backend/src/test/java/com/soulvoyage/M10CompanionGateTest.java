package com.soulvoyage;

import com.soulvoyage.agent.companion.CompanionService;
import com.soulvoyage.common.api.ErrorCode;
import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.domain.companion.CompanionSessionEntity;
import com.soulvoyage.domain.companion.CompanionSessionRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.common.time.BusinessCalendar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上线门禁（下篇·十）：漫聊 200 轮/日软上限与用户级滑动窗限流的专项用例。
 * 两条护栏都是"只关门不毁数据"——被拒的那一轮不落库、不计费，历史轮次仍完整可读。
 */
@SpringBootTest
@ActiveProfiles("test")
class M10CompanionGateTest {

    @Autowired CompanionService companion;
    @Autowired CompanionSessionRepository sessionRepo;
    @Autowired UserRepository userRepo;
    @Autowired BusinessCalendar cal;
    @Autowired PasswordEncoder encoder;

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("gt" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("Passw0rd!2026"));
        return userRepo.save(u).getId();
    }

    private String chat(int i) {
        return "第 " + i + " 句：最近睡眠不太好，白天没什么劲，想理一理。";
    }

    @Test
    void softCapClosesTheDayAndRejectsSilently() throws Exception {
        long uid = newUser();
        long sid = companion.openOrReuse(uid).sessionId();
        // 不真跑 199 轮 LLM：另起一段已封口会话把今日轮量顶到软上限门口
        CompanionSessionEntity bulk = new CompanionSessionEntity();
        bulk.setUserId(uid);
        bulk.setChatDate(cal.today());
        bulk.setSegmentNo(2);
        bulk.setStatus("SEALED");
        bulk.setTurns(CompanionService.SOFT_CAP_TURNS_PER_DAY - 1);
        sessionRepo.save(bulk);

        var last = companion.turn(uid, sid, chat(1));
        assertEquals(0, last.remainingToday(), "第 200 轮用完即到位");

        BizException e = assertThrows(BizException.class, () -> companion.turn(uid, sid, chat(2)));
        assertTrue(e.getMessage().contains("200 轮"), "拒绝话术要说明原因，不能只报错");
        assertEquals(1, sessionRepo.findById(sid).orElseThrow().getTurns(), "被拒的一轮不落库");
        assertEquals(1, companion.transcript(uid, sid).get("turnList").size(), "软上限不毁历史数据");
    }

    @Test
    void slidingWindowThrottlesPerUserNotPerSession() {
        long uid = newUser();
        long sid = companion.openOrReuse(uid).sessionId();
        for (int i = 1; i <= CompanionService.RATE_LIMIT_PER_MIN; i++) {
            int n = i;
            assertDoesNotThrow(() -> companion.turn(uid, sid, chat(n)), "第 " + n + " 条不该被限");
        }
        BizException e = assertThrows(BizException.class,
                () -> companion.turn(uid, sid, chat(CompanionService.RATE_LIMIT_PER_MIN + 1)));
        assertEquals(ErrorCode.LLM_RATE_LIMIT.code(), e.getErrorCode().code(), "限流走 4002");

        // 窗是用户级：换一段新会话仍在同一分钟窗内，照样被限
        companion.end(uid, sid);
        long next = companion.openOrReuse(uid).sessionId();
        assertNotEquals(sid, next);
        assertThrows(BizException.class, () -> companion.turn(uid, next, chat(22)));

        // 别的账号不受牵连
        long other = newUser();
        long osid = companion.openOrReuse(other).sessionId();
        assertDoesNotThrow(() -> companion.turn(other, osid, chat(1)));
    }
}
