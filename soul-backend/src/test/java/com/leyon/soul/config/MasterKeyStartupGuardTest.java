package com.leyon.soul.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * B-08：主密钥守卫（2026-11-20 安全审查后语义收紧）。
 *
 * <p>旧行为只在 {@code prod} 下拦截，而 docker-compose 从未设 {@code SPRING_PROFILES_ACTIVE}，
 * 于是"没 profile"的真实部署反而放行。现改为白名单：仅 dev/test/local 允许空 MK。
 */
class MasterKeyStartupGuardTest {

    private static MockEnvironment env(String[] profiles, String key) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        e.setProperty("soul.security.master-key", key);
        return e;
    }

    private static void guard(MockEnvironment e) {
        new MasterKeyStartupGuard(e).afterPropertiesSet();
    }

    @Test
    void prodWithoutKeyFailsFast() {
        assertThrows(IllegalStateException.class, () -> guard(env(new String[]{"prod"}, "")));
        assertThrows(IllegalStateException.class, () -> guard(env(new String[]{"prod"}, "   ")));
    }

    @Test
    void prodWithKeyPasses() {
        assertDoesNotThrow(() -> guard(env(new String[]{"prod"}, "Mm6f7NlhWcVrKrTPK+y0Pln1secKp4WiHNtnNbzlBiE=")));
    }

    /** 这条是本轮修复的核心：compose 之前就是这个状态（一个 profile 都没设） */
    @Test
    void noProfileIsTreatedAsProduction() {
        assertThrows(IllegalStateException.class, () -> guard(env(new String[0], "")));
    }

    @Test
    void unknownProfileIsNotADevProfile() {
        assertThrows(IllegalStateException.class, () -> guard(env(new String[]{"staging"}, "")));
    }

    @Test
    void devProfilesStillAllowEphemeralKey() {
        assertDoesNotThrow(() -> guard(env(new String[]{"dev"}, "")));
        assertDoesNotThrow(() -> guard(env(new String[]{"test"}, "")));
        assertDoesNotThrow(() -> guard(env(new String[]{"local"}, "")));
    }

    @Test
    void prodPlusDevMixedProfileIsAllowed() {
        // 本地联调常见 `--spring.profiles.active=prod,local`：只要有一个开发语义 profile 就放行（并打警告）
        assertDoesNotThrow(() -> guard(env(new String[]{"prod", "local"}, "")));
    }
}
