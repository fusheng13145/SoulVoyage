package com.soulvoyage.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** B-08：prod profile 缺主密钥必须启动失败；dev/default 保持原体验。 */
class MasterKeyStartupGuardTest {

    private static MockEnvironment env(String profile, String key) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profile);
        e.setProperty("soulvoyage.security.master-key", key);
        return e;
    }

    @Test
    void prodWithoutKeyFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new MasterKeyStartupGuard(env("prod", "")).afterPropertiesSet());
    }

    @Test
    void prodWithKeyPasses() {
        assertDoesNotThrow(() ->
                new MasterKeyStartupGuard(env("prod", "Mm6f7NlhWcVrKrTPK+y0Pln1secKp4WiHNtnNbzlBiE="))
                        .afterPropertiesSet());
    }

    @Test
    void devProfileKeepsEphemeralKeyExperience() {
        assertDoesNotThrow(() -> new MasterKeyStartupGuard(env("default", "")).afterPropertiesSet());
        assertDoesNotThrow(() -> new MasterKeyStartupGuard(env("test", "")).afterPropertiesSet());
    }
}
