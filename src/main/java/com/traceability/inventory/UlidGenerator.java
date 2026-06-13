package com.traceability.inventory;

import java.security.SecureRandom;

/** Generates Crockford base-32 ULIDs: 48-bit ms timestamp + 80-bit random, 26 chars. */
public final class UlidGenerator {

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private UlidGenerator() {}

    public static String generate() {
        long ts = System.currentTimeMillis();
        byte[] rnd = new byte[10];
        RNG.nextBytes(rnd);

        char[] c = new char[26];

        // 10 chars — 48-bit timestamp packed into 50 bits (top 2 bits always 0)
        c[0] = ALPHABET[(int) ((ts >>> 45) & 0x1F)];
        c[1] = ALPHABET[(int) ((ts >>> 40) & 0x1F)];
        c[2] = ALPHABET[(int) ((ts >>> 35) & 0x1F)];
        c[3] = ALPHABET[(int) ((ts >>> 30) & 0x1F)];
        c[4] = ALPHABET[(int) ((ts >>> 25) & 0x1F)];
        c[5] = ALPHABET[(int) ((ts >>> 20) & 0x1F)];
        c[6] = ALPHABET[(int) ((ts >>> 15) & 0x1F)];
        c[7] = ALPHABET[(int) ((ts >>> 10) & 0x1F)];
        c[8] = ALPHABET[(int) ((ts >>>  5) & 0x1F)];
        c[9] = ALPHABET[(int) ( ts         & 0x1F)];

        // 16 chars — 80-bit random (10 bytes × 8 bits / 5 bits per char = 16 chars exactly)
        c[10] = ALPHABET[(rnd[0] & 0xFF) >>> 3];
        c[11] = ALPHABET[((rnd[0] & 0x07) << 2) | ((rnd[1] & 0xFF) >>> 6)];
        c[12] = ALPHABET[((rnd[1] & 0xFF) >>> 1) & 0x1F];
        c[13] = ALPHABET[((rnd[1] & 0x01) << 4) | ((rnd[2] & 0xFF) >>> 4)];
        c[14] = ALPHABET[((rnd[2] & 0x0F) << 1) | ((rnd[3] & 0xFF) >>> 7)];
        c[15] = ALPHABET[((rnd[3] & 0xFF) >>> 2) & 0x1F];
        c[16] = ALPHABET[((rnd[3] & 0x03) << 3) | ((rnd[4] & 0xFF) >>> 5)];
        c[17] = ALPHABET[rnd[4] & 0x1F];
        c[18] = ALPHABET[(rnd[5] & 0xFF) >>> 3];
        c[19] = ALPHABET[((rnd[5] & 0x07) << 2) | ((rnd[6] & 0xFF) >>> 6)];
        c[20] = ALPHABET[((rnd[6] & 0xFF) >>> 1) & 0x1F];
        c[21] = ALPHABET[((rnd[6] & 0x01) << 4) | ((rnd[7] & 0xFF) >>> 4)];
        c[22] = ALPHABET[((rnd[7] & 0x0F) << 1) | ((rnd[8] & 0xFF) >>> 7)];
        c[23] = ALPHABET[((rnd[8] & 0xFF) >>> 2) & 0x1F];
        c[24] = ALPHABET[((rnd[8] & 0x03) << 3) | ((rnd[9] & 0xFF) >>> 5)];
        c[25] = ALPHABET[rnd[9] & 0x1F];

        return new String(c);
    }
}
