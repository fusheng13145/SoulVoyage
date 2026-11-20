package com.leyon.soul;

import com.leyon.soul.domain.notify.UserPreferencesEntity;
import com.leyon.soul.domain.notify.UserPreferencesRepository;
import com.leyon.soul.domain.user.UserEntity;
import com.leyon.soul.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B-09：可变聚合加 @Version 后，多端并发写同一行必须有一方冲突，不能静默覆盖。
 * 用 UserPreferences 作为代表（四张表的映射方式一致）。
 */
@SpringBootTest
@ActiveProfiles("test")
class OptimisticLockTest {

    @Autowired UserPreferencesRepository prefs;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired TransactionTemplate tx;

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("ol" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("SvFixture!2026"));
        return users.save(u).getId();
    }

    @Test
    void staleUpdateIsRejectedAndWinnerKept() {
        long uid = newUser();
        Long id = tx.execute(s -> {
            UserPreferencesEntity p = new UserPreferencesEntity();
            p.setUserId(uid);
            p.setUpdatedAt(Instant.now());
            return prefs.saveAndFlush(p).getId();
        });

        // 模拟两端各自读到的旧快照（version=0）
        UserPreferencesEntity stale = tx.execute(s -> prefs.findById(id).orElseThrow());

        // 先写方成功，版本号 +1
        tx.executeWithoutResult(s -> {
            UserPreferencesEntity p = prefs.findById(id).orElseThrow();
            p.setTheme("dark");
            prefs.saveAndFlush(p);
        });

        // 后写方携带旧版本 → 必须冲突
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> tx.executeWithoutResult(s -> {
            stale.setTheme("light");
            prefs.saveAndFlush(stale);
        }));

        assertEquals("dark", tx.execute(s -> prefs.findById(id).orElseThrow().getTheme()),
                "失败方不得覆盖成功方的写入");
    }
}
