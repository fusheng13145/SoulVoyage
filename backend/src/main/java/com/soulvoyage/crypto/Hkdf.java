package com.soulvoyage.crypto;

/**
 * HKDF-SHA256（RFC 5869）实现，避免为单一算法引入 BouncyCastle。
 * 用于从主密钥 MK 派生 AES-GCM 数据密钥的使用上下文密钥等。
 */
final class Hkdf {

    private Hkdf() {}

    static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int len) {
        byte[] prk = hmac(salt == null ? new byte[32] : salt, ikm);
        byte[] okm = new byte[len];
        byte[] prev = new byte[0];
        int off = 0;
        byte counter = 1;
        while (off < len) {
            byte[] input = concat(prev, info == null ? new byte[0] : info, new byte[]{counter});
            prev = hmac(prk, input);
            int n = Math.min(prev.length, len - off);
            System.arraycopy(prev, 0, okm, off, n);
            off += n;
            counter++;
        }
        return okm;
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HKDF failed", e);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
