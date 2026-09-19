package com.soulvoyage.common.util;

import java.security.SecureRandom;
import java.time.Instant;

/** Crockford Base32 单调 ULID（48bit 毫秒时间戳 + 80bit 随机），用于 task_no / message_no */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {}

    public static String next() {
        long ts = Instant.now().toEpochMilli();
        byte[] rnd = new byte[10];
        RANDOM.nextBytes(rnd);

        char[] out = new char[26];
        for (int i = 0; i < 10; i++) {                        // 48bit timestamp, Crockford base32
            out[i] = ALPHABET[(int) ((ts >>> ((9 - i) * 5)) & 0x1F)];
        }
        long v = 0;
        int bits = 0, idx = 10;
        for (byte b : rnd) {                                  // randomness
            v = (v << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out[idx++] = ALPHABET[(int) ((v >>> (bits - 5)) & 0x1F)];
                bits -= 5;
            }
        }
        return new String(out);
    }
}
