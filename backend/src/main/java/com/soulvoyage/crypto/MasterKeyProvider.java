package com.soulvoyage.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 主密钥 MK：生产从环境变量 SV_MASTER_KEY(Base64, 32B) 注入（后续接 KMS / docker secret）。
 * 开发环境未配置时生成随机临时密钥并告警——重启后旧密文不可解，仅限本地调试。
 */
@Slf4j
@Component
public class MasterKeyProvider {

    private final byte[] masterKey;

    public MasterKeyProvider(@Value("${soulvoyage.security.master-key:}") String configured) {
        if (configured != null && !configured.isBlank()) {
            byte[] k = Base64.getDecoder().decode(configured.trim());
            if (k.length != 32) {
                throw new IllegalStateException("SV_MASTER_KEY must be 32 bytes (base64)");
            }
            this.masterKey = k;
        } else {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            this.masterKey = k;
            log.warn("SV_MASTER_KEY not configured -> ephemeral dev master key generated. " +
                     "Encrypted data will NOT be recoverable after restart. DEV ONLY.");
        }
    }

    public byte[] key() {
        return masterKey.clone();
    }
}
