package com.soulvoyage.crypto;

import com.soulvoyage.common.exception.BizException;
import com.soulvoyage.common.api.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;

@Slf4j
@Service
public class EnvelopeCryptoService implements CryptoService {

    private static final int DEK_LEN = 32;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MasterKeyProvider mk;
    private final DataKeyRepository keyRepo;
    private final TransactionTemplate newTx;

    public EnvelopeCryptoService(MasterKeyProvider mk, DataKeyRepository keyRepo,
                                 PlatformTransactionManager txManager) {
        this.mk = mk;
        this.keyRepo = keyRepo;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public byte[] encryptUserField(long userId, String plaintext) {
        DataKeyEntity dk = currentKey(userId);
        byte[] key = unwrap(dk);
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Arrays.fill(key, (byte) 0);
            byte[] out = new byte[1 + IV_LEN + ct.length];
            out[0] = (byte) (int) dk.getVersion();
            System.arraycopy(iv, 0, out, 1, IV_LEN);
            System.arraycopy(ct, 0, out, 1 + IV_LEN, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("field encrypt failed", e);
        }
    }

    @Override
    public String decryptUserField(long userId, byte[] blob) {
        if (blob == null || blob.length < 1 + IV_LEN + 16) {
            throw new IllegalStateException("invalid ciphertext");
        }
        int version = blob[0] & 0xFF;
        DataKeyEntity dk = keyRepo.findByOwnerUserIdAndVersion(userId, version)
                .orElseThrow(() -> new BizException(ErrorCode.FORBIDDEN, "数据密钥不可用"));
        byte[] key = unwrap(dk);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, blob, 1, IV_LEN));
            byte[] pt = c.doFinal(blob, 1 + IV_LEN, blob.length - 1 - IV_LEN);
            return new String(pt, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("field decrypt failed (key destroyed?)", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    @Transactional
    public void destroyUserKeys(long userId) {
        keyRepo.findByOwnerUserIdAndStatusNot(userId, (short) 3).forEach(k -> {
            byte[] noise = new byte[32];
            RANDOM.nextBytes(noise);              // 覆写为随机噪声：即使 MK 泄露也无法还原 DEK
            k.setEncMasterKeyRef(noise);
            k.setStatus((short) 3);
            k.setDestroyedAt(Instant.now());
        });
        log.info("user {} keys destroyed (crypto-shredding)", userId);
    }

    private synchronized DataKeyEntity currentKey(long userId) {
        return keyRepo.findFirstByOwnerUserIdAndStatusOrderByVersionDesc(userId, (short) 1)
                .orElseGet(() -> createKey(userId));
    }

    /** 直读 data_key 元数据；无启用密钥（未产生过密文）返回 0，不触发建钥 */
    @Override
    public int activeKeyVersion(long userId) {
        return keyRepo.findFirstByOwnerUserIdAndStatusOrderByVersionDesc(userId, (short) 1)
                .map(DataKeyEntity::getVersion).orElse(0);
    }

    /**
     * 建钥必须独立提交（REQUIRES_NEW）：外层加密事务（如日记 attachTask）与流水线虚拟线程
     * 可能同时为首条密文建钥——若并入外层事务，synchronized 挡不住"未提交行互不可见"，
     * 会产生两条 version=1 的 DEK 使解密抛 IncorrectResultSize（uk_owner_ver 亦会拒绝）。
     */
    private DataKeyEntity createKey(long userId) {
        return newTx.execute(status -> {
            var existing = keyRepo.findFirstByOwnerUserIdAndStatusOrderByVersionDesc(userId, (short) 1);
            if (existing.isPresent()) return existing.get();
            byte[] dek = new byte[DEK_LEN];
            RANDOM.nextBytes(dek);
            DataKeyEntity e = new DataKeyEntity();
            e.setOwnerUserId(userId);
            e.setVersion(nextVersion(userId));
            e.setEncMasterKeyRef(wrapDek(dek));
            e.setStatus((short) 1);
            Arrays.fill(dek, (byte) 0);
            return keyRepo.save(e);
        });
    }

    private int nextVersion(long userId) {
        return keyRepo.findFirstByOwnerUserIdAndStatusOrderByVersionDesc(userId, (short) 3)
                .map(k -> k.getVersion() + 1).orElse(1);
    }

    private byte[] wrapDek(byte[] dek) {
        return aesGcm(mk.key(), dek);
    }

    private byte[] unwrap(DataKeyEntity dk) {
        if (dk.getEncMasterKeyRef() == null || dk.getStatus() == 3) {
            throw new BizException(ErrorCode.FORBIDDEN, "数据已销毁，无法解密");
        }
        return aesGcmDecrypt(mk.key(), dk.getEncMasterKeyRef());
    }

    // ---- AES-GCM primitives (MK 包裹层使用固定零上下文，仅保护 DEK) ----

    static byte[] aesGcm(byte[] key, byte[] plain) {
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    static byte[] aesGcmDecrypt(byte[] key, byte[] blob) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN));
            return c.doFinal(blob, IV_LEN, blob.length - IV_LEN);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        }
    }
}
