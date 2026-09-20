package com.soulvoyage;

import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.time.BusinessCalendar;
import com.soulvoyage.crypto.CryptoService;
import com.soulvoyage.crypto.DataKeyEntity;
import com.soulvoyage.crypto.DataKeyRepository;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O4 加解密往返 / 密钥销毁（crypto-shredding）/ 业务时区跨日边界：
 * 信封首字节即密钥版本；销毁后旧密文永久不可解，再写入自动开新版本密钥；
 * 日期归属以 soulvoyage.business-zone 切日，UTC 傍晚仍属业务"明天"。
 */
@SpringBootTest
@ActiveProfiles("test")
class M9CryptoZoneTest {

    @Autowired CryptoService crypto;
    @Autowired DataKeyRepository keyRepo;
    @Autowired UserRepository userRepo;
    @Autowired PasswordEncoder encoder;
    @Autowired BusinessCalendar cal;

    private long newUser() {
        UserEntity u = new UserEntity();
        u.setUsername("cz" + System.nanoTime());
        u.setNickname("t");
        u.setPasswordHash(encoder.encode("Passw0rd!2026"));
        return userRepo.save(u).getId();
    }

    @Test
    void roundTripCarriesKeyVersionAndRejectsTamper() {
        long uid = newUser();
        assertEquals(0, crypto.activeKeyVersion(uid), "未产生密文前不建钥");

        String plain = "今晚的月亮很圆🌕——信封加密往返验证，含中文与表情符号。";
        byte[] blob = crypto.encryptUserField(uid, plain);
        assertEquals(1, crypto.activeKeyVersion(uid));
        assertEquals(1, blob[0] & 0xFF, "信封首字节=密钥版本");
        assertEquals(plain, crypto.decryptUserField(uid, blob));

        // 每次加密 IV 随机：同明文不同密文
        assertFalse(java.util.Arrays.equals(blob, crypto.encryptUserField(uid, plain)));

        byte[] tampered = blob.clone();
        tampered[tampered.length - 1] ^= 0x01;   // 破坏 GCM 认证标签
        assertThrows(IllegalStateException.class, () -> crypto.decryptUserField(uid, tampered));
        assertThrows(IllegalStateException.class, () -> crypto.decryptUserField(uid, new byte[]{1, 2, 3}));
    }

    @Test
    void destroyMakesOldCiphertextUnreadableAndNextWriteGetsNewKey() {
        long uid = newUser();
        byte[] old = crypto.encryptUserField(uid, "将被销毁的数据");
        assertEquals(1, crypto.activeKeyVersion(uid));

        crypto.destroyUserKeys(uid);
        List<DataKeyEntity> destroyed = keyRepo.findByOwnerUserIdAndStatus(uid, (short) 3);
        assertEquals(1, destroyed.size());
        assertEquals(0, keyRepo.findByOwnerUserIdAndStatus(uid, (short) 1).size());

        assertThrows(BizException.class, () -> crypto.decryptUserField(uid, old),
                "密钥销毁后旧密文必须永久不可解");

        // 再写入：开新版本密钥，新数据可解，旧数据仍不可解
        byte[] fresh = crypto.encryptUserField(uid, "销毁后的新写入");
        assertEquals(2, fresh[0] & 0xFF, "新版本密钥从 v2 续起");
        assertEquals(2, crypto.activeKeyVersion(uid));
        assertEquals("销毁后的新写入", crypto.decryptUserField(uid, fresh));
        assertThrows(BizException.class, () -> crypto.decryptUserField(uid, old));
    }

    @Test
    void businessZoneDayBoundaryIsNotUtcDay() {
        // 2026-09-19T16:30Z：UTC 仍是 19 日，业务时区（Asia/Shanghai）已是 20 日凌晨——跨日归属必须跟随时区
        LocalDate utcDay = Instant.parse("2026-09-19T16:30:00Z").atZone(java.time.ZoneOffset.UTC).toLocalDate();
        LocalDate bizDay = Instant.parse("2026-09-19T16:30:00Z").atZone(cal.zone()).toLocalDate();
        assertEquals(LocalDate.of(2026, 9, 19), utcDay);
        assertEquals(LocalDate.of(2026, 9, 20), bizDay);
        // 边界前 1 秒仍归 19 日
        assertEquals(LocalDate.of(2026, 9, 19),
                Instant.parse("2026-09-19T15:59:59Z").atZone(cal.zone()).toLocalDate());
        assertEquals("Asia/Shanghai", cal.zone().getId(), "测试环境业务时区必须显式钉死，防机器时区漂移");
        assertEquals(LocalDate.now(cal.zone()), cal.today());
    }
}
