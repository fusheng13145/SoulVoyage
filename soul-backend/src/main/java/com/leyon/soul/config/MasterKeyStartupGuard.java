package com.leyon.soul.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

/**
 * B-08：主密钥生产守卫（C 目录零改动，crypto/** 不碰）。
 *
 * <p>语义修正（2026-11-20 安全审查）：原先只在 {@code prod} profile 生效，而 docker-compose 从未设置
 * {@code SPRING_PROFILES_ACTIVE}，导致这道防线在真实部署路径上从不触发，空 MK 会退化为"启动时临时密钥"——
 * 重启后全部历史密文永久不可解（数据丢失，不是泄露）。现在改成<b>白名单式</b>：
 * 只有 dev/test/local 这三种开发语义下才允许空 MK；其余情况（含"没设任何 profile"的直接 java -jar）一律启动失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MasterKeyStartupGuard implements InitializingBean {

    /** 允许"临时密钥 + 告警"的开发语义 profile；其余一律要求显式配置 */
    private static final String[] DEV_PROFILES = {"test", "dev", "local"};

    private final Environment env;

    @Override
    public void afterPropertiesSet() {
        String key = env.getProperty("soul.security.master-key", "");
        boolean devLike = Arrays.stream(env.getActiveProfiles())
                .map(p -> p.toLowerCase(Locale.ROOT))
                .anyMatch(p -> Arrays.asList(DEV_PROFILES).contains(p));
        if (key != null && !key.isBlank()) {
            log.info("master key guard passed (active profiles={})", Arrays.toString(env.getActiveProfiles()));
            return;
        }
        if (devLike) {
            log.warn("SV_MASTER_KEY 为空：本次使用临时主密钥（仅限开发），重启后历史密文将不可解");
            return;
        }
        throw new IllegalStateException(
                "必须配置 SV_MASTER_KEY（Base64 32 字节）：当前 profiles="
                        + Arrays.toString(env.getActiveProfiles())
                        + "，非 dev/test/local 时禁止使用临时主密钥（重启会让历史密文永久不可解）");
    }
}
