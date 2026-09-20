package com.soulvoyage.crypto;

/**
 * 信封加密服务（手册 §7.1）。
 * 密文格式：[1B keyVersion][12B IV][ciphertext][16B GCM tag]
 * DEK：随机 32B，登记于 data_key 表，用 MK(AES-GCM) 包裹存储；
 * 注销即遗忘：销毁用户 DEK（置空密文引用 + status=3），数据物理不可恢复。
 */
public interface CryptoService {

    byte[] encryptUserField(long userId, String plaintext);

    String decryptUserField(long userId, byte[] blob);

    /** 用户注销：销毁其全部数据密钥 */
    void destroyUserKeys(long userId);

    /** 当前启用 DEK 的版本号（技术债 4：读元数据，不做探针重加密）；无密钥返回 0 */
    int activeKeyVersion(long userId);
}
