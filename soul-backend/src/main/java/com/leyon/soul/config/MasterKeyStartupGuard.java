package com.leyon.soul.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * B-08：主密钥生产守卫（C 目录零改动，crypto/** 不碰）。
 *
 * <p>prod profile 下未配置 SV_MASTER_KEY 时直接启动失败：临时密钥会让重启后的全部密文不可解。
 * dev/test/default 保留原有"临时密钥 + 告警"的开发体验。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MasterKeyStartupGuard implements InitializingBean {

    private final Environment env;

    @Override
    public void afterPropertiesSet() {
        boolean prod = Arrays.asList(env.getActiveProfiles()).contains("prod");
        String key = env.getProperty("soul.security.master-key", "");
        if (prod && (key == null || key.isBlank())) {
            throw new IllegalStateException(
                    "prod profile 必须配置 SV_MASTER_KEY（Base64 32 字节）：临时密钥会导致重启后历史密文不可解");
        }
        if (prod) log.info("master key guard passed (prod profile, key configured)");
    }
}
