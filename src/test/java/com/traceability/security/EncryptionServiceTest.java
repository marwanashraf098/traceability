package com.traceability.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * K1 key ring (2026-11-28) — EncryptionService, plain JUnit (no Spring, no DB).
 *
 *   g1/g2  golden: ciphertexts produced by the PRE-key-ring code (origin/main d7b23d2a) with the zero
 *          legacy key — the key prod runs on — still decrypt, whatever the write version
 *   l1     v1 output decrypts with a verbatim copy of the pre-key-ring algorithm (and back)
 *   r1/r2  both write versions round-trip, with and without AAD; v2 output is "v2:"+Base64
 *   r3     a v2 writer reads legacy values; a v1 writer with the V2 key reads v2 values (K2 → K1 rollback)
 *   t1-t3  a tampered / re-labelled v2 value fails and never falls back to the legacy key; a v2 value
 *          with no V2 key fails; tampered legacy fails
 *   a1     wrong AAD fails for both versions
 *   i1     malformed input (not Base64, shorter than IV + tag) fails cleanly as IllegalStateException
 *   s1-s7  each startup refusal; v1 without a V2 key starts
 *   m1     no plaintext, ciphertext or key material in any exception message, no cause chained
 */
class EncryptionServiceTest {

    static final String ZERO_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    /** Test-only V2 key (same as src/test/resources/application.properties). */
    static final String V2_KEY = "TdbPDL8k3c7zPbbyWnh/jdjyaBaT8kWNMdh2ICsbC04=";

    // Produced 2026-11-28 by origin/main's (pre-key-ring) EncryptionService, legacy key = ZERO_KEY.
    static final String GOLDEN_PLAIN = "shpat_golden-legacy-token-ü";
    static final String GOLDEN_CT =
        "dEcxB9GftJcDJBInK8hNy7RHWwjxiRNOPSjCt/MKBAMN041WtaO0D4FF/XedmWvTt2fpFClQETE=";
    static final String GOLDEN_AAD_PLAIN = "{\"iban\":\"EG380019000500000000263180002\"}";
    static final String GOLDEN_AAD =
        "11111111-1111-1111-1111-111111111111|22222222-2222-2222-2222-222222222222";
    static final String GOLDEN_AAD_CT =
        "SRAXLKtY9vYXY11eKwODKhxxuIlmJFkHrCEsDDZM3uJbOBQ2wajQ73D/cHsgvSsSKR/AEegNB9dcJ/VQ3gWADZI1Mx8=";

    static EncryptionService v1() { return new EncryptionService(ZERO_KEY, V2_KEY, "v1"); }
    static EncryptionService v2() { return new EncryptionService(ZERO_KEY, V2_KEY, "v2"); }
    static EncryptionService legacyOnly() { return new EncryptionService(ZERO_KEY, "", "v1"); }

    // ── golden ───────────────────────────────────────────────────────────────────────────────

    @Test
    void g1_golden_legacyCiphertextFromPreKeyRingCode_decryptsUnderEveryConfiguration() {
        for (EncryptionService e : List.of(v1(), v2(), legacyOnly())) {
            assertThat(e.decrypt(GOLDEN_CT)).isEqualTo(GOLDEN_PLAIN);
        }
    }

    @Test
    void g2_golden_legacyAadCiphertext_decryptsUnderEveryConfiguration() {
        for (EncryptionService e : List.of(v1(), v2(), legacyOnly())) {
            assertThat(e.decrypt(GOLDEN_AAD_CT, GOLDEN_AAD)).isEqualTo(GOLDEN_AAD_PLAIN);
        }
    }

    // ── v1 == pre-key-ring format ──────────────────────────────────────────────────────────

    @Test
    void l1_v1Output_isTodaysFormat_bothDirections() {
        PreKeyRingAlgorithm old = new PreKeyRingAlgorithm(ZERO_KEY);
        for (EncryptionService e : List.of(v1(), legacyOnly())) {
            String ct = e.encrypt(GOLDEN_PLAIN);
            assertThat(ct).doesNotStartWith("v2:");
            assertThat(Base64.getDecoder().decode(ct))
                .hasSize(12 + GOLDEN_PLAIN.getBytes(StandardCharsets.UTF_8).length + 16);
            assertThat(old.decrypt(ct)).isEqualTo(GOLDEN_PLAIN);
            String ctAad = e.encrypt(GOLDEN_AAD_PLAIN, GOLDEN_AAD);
            assertThat(old.decrypt(ctAad, GOLDEN_AAD)).isEqualTo(GOLDEN_AAD_PLAIN);
            // and the old algorithm's output opens with the new class
            assertThat(e.decrypt(old.encrypt("x-123"))).isEqualTo("x-123");
            assertThat(e.decrypt(old.encrypt("y", "a|b"), "a|b")).isEqualTo("y");
        }
    }

    // ── round trips ────────────────────────────────────────────────────────────────────────

    @Test
    void r1_v1_roundTrips_withAndWithoutAad() {
        EncryptionService e = v1();
        assertThat(e.writeVersion()).isEqualTo(EncryptionService.WriteVersion.V1);
        for (String p : List.of("", "a", GOLDEN_PLAIN, "x".repeat(5000))) {
            assertThat(e.decrypt(e.encrypt(p))).isEqualTo(p);
            assertThat(e.decrypt(e.encrypt(p, "t|r"), "t|r")).isEqualTo(p);
        }
        assertThat(e.encrypt("same")).isNotEqualTo(e.encrypt("same"));   // fresh IV per call
    }

    @Test
    void r2_v2_roundTrips_withAndWithoutAad_prefixed_andNotLegacyReadable() {
        EncryptionService e = v2();
        assertThat(e.writeVersion()).isEqualTo(EncryptionService.WriteVersion.V2);
        PreKeyRingAlgorithm old = new PreKeyRingAlgorithm(ZERO_KEY);
        for (String p : List.of("", "a", GOLDEN_PLAIN, "x".repeat(5000))) {
            String ct = e.encrypt(p);
            assertThat(ct).startsWith("v2:");
            assertThat(Base64.getDecoder().decode(ct.substring(3)))
                .hasSize(12 + p.getBytes(StandardCharsets.UTF_8).length + 16);
            assertThat(e.decrypt(ct)).isEqualTo(p);
            String ctAad = e.encrypt(p, "t|r");
            assertThat(ctAad).startsWith("v2:");
            assertThat(e.decrypt(ctAad, "t|r")).isEqualTo(p);
            // the legacy key can't open the body of a v2 value
            assertThatThrownBy(() -> old.decrypt(ct.substring(3))).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void r3_mixedRows_v2WriterReadsLegacy_v1WriterReadsV2() {
        String legacy = v1().encrypt("legacy-row");
        String fresh  = v2().encrypt("v2-row");
        assertThat(v2().decrypt(legacy)).isEqualTo("legacy-row");   // K2a: old rows still read
        assertThat(v1().decrypt(fresh)).isEqualTo("v2-row");        // K2 → K1 rollback is safe
        assertThat(v1().decrypt(v2().encrypt("p", "t|r"), "t|r")).isEqualTo("p");
        assertThat(v2().decrypt(v1().encrypt("p", "t|r"), "t|r")).isEqualTo("p");
    }

    // ── tampering / no fallback ────────────────────────────────────────────────────────────

    @Test
    void t1_tamperedV2_fails_neverFallsBackToLegacy() {
        EncryptionService e = v2();
        String ct = e.encrypt("secret-token");
        byte[] raw = Base64.getDecoder().decode(ct.substring(3));
        for (int i : new int[] {0, 12, raw.length - 1}) {      // IV, ciphertext, tag
            byte[] t = raw.clone();
            t[i] ^= 0x01;
            String tampered = "v2:" + Base64.getEncoder().encodeToString(t);
            assertThatThrownBy(() -> e.decrypt(tampered)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Decryption failed");
        }
        // A legacy-key ciphertext re-labelled "v2:" must NOT open: the prefix picks the V2 key only.
        String relabelled = "v2:" + GOLDEN_CT;
        for (EncryptionService s : List.of(v1(), v2())) {
            assertThatThrownBy(() -> s.decrypt(relabelled)).isInstanceOf(IllegalStateException.class);
        }
        // …and a v2 body with the prefix stripped doesn't open with the legacy key either.
        assertThatThrownBy(() -> e.decrypt(ct.substring(3))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void t2_v2Value_withoutV2Key_failsCleanly() {
        String ct = v2().encrypt("secret-token");
        assertThatThrownBy(() -> legacyOnly().decrypt(ct)).isInstanceOf(IllegalStateException.class)
            .hasMessage("Decryption failed (v2 value, no V2 key configured)");
    }

    @Test
    void t3_tamperedLegacy_fails() {
        byte[] raw = Base64.getDecoder().decode(GOLDEN_CT);
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> v2().decrypt(tampered)).isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("Decryption failed");
    }

    // ── AAD ────────────────────────────────────────────────────────────────────────────────

    @Test
    void a1_wrongAad_failsForBothVersions() {
        for (EncryptionService e : List.of(v1(), v2())) {
            String ct = e.encrypt("details", "t1|r1");
            assertThat(e.decrypt(ct, "t1|r1")).isEqualTo("details");
            assertThatThrownBy(() -> e.decrypt(ct, "t1|r2")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> e.decrypt(ct)).isInstanceOf(IllegalStateException.class);
            String noAad = e.encrypt("details");
            assertThatThrownBy(() -> e.decrypt(noAad, "t1|r1")).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> v2().decrypt(GOLDEN_AAD_CT, GOLDEN_AAD + "x"))
            .isInstanceOf(IllegalStateException.class);
    }

    // ── malformed input ────────────────────────────────────────────────────────────────────

    @Test
    void i1_malformedInput_failsCleanly() {
        String b27 = Base64.getEncoder().encodeToString(new byte[27]);   // < IV(12) + tag(16)
        for (EncryptionService e : List.of(v1(), v2())) {
            for (String bad : List.of("", "AAAA", b27, "v2:", "v2:AAAA", "v2:" + b27, "not base64!", "v2:%%%%")) {
                Throwable t = catchThrowable(() -> e.decrypt(bad));
                assertThat(t).as(bad).isInstanceOf(IllegalStateException.class);
                assertThat(t.getMessage()).startsWith("Decryption failed");
                Throwable ta = catchThrowable(() -> e.decrypt(bad, "t|r"));
                assertThat(ta).as(bad).isInstanceOf(IllegalStateException.class);
            }
            // exactly IV + tag (empty plaintext) is a valid value
            assertThat(e.decrypt(e.encrypt(""))).isEmpty();
        }
    }

    // ── startup refusals ───────────────────────────────────────────────────────────────────

    @Test
    void s1_writeV2_withoutV2Key_refuses() {
        for (String missing : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> new EncryptionService(ZERO_KEY, missing, "v2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_ENCRYPTION_KEY_V2 is required");
        }
    }

    @Test
    void s2_v2EqualsLegacy_refuses_inBothWriteVersions() {
        String legacy = Base64.getEncoder().encodeToString(filled((byte) 7));
        for (String wv : List.of("v1", "v2")) {
            assertThatThrownBy(() -> new EncryptionService(legacy, legacy, wv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("equals APP_ENCRYPTION_KEY");
        }
    }

    @Test
    void s3_v2AllZero_refuses_inBothWriteVersions() {
        String legacy = Base64.getEncoder().encodeToString(filled((byte) 7));
        for (String wv : List.of("v1", "v2")) {
            assertThatThrownBy(() -> new EncryptionService(legacy, ZERO_KEY, wv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("all zero");
            // and the zero V2 with the (prod) zero legacy key is refused too
            assertThatThrownBy(() -> new EncryptionService(ZERO_KEY, ZERO_KEY, wv))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void s4_v2WrongLength_refuses_inBothWriteVersions() {
        for (int n : new int[] {1, 16, 31, 33, 64}) {
            String k = Base64.getEncoder().encodeToString(Arrays.copyOf(filled((byte) 9), n));
            for (String wv : List.of("v1", "v2")) {
                assertThatThrownBy(() -> new EncryptionService(ZERO_KEY, k, wv))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exactly 32 bytes").hasMessageContaining("got " + n);
            }
        }
    }

    @Test
    void s5_v2NotBase64_refuses() {
        for (String wv : List.of("v1", "v2")) {
            assertThatThrownBy(() -> new EncryptionService(ZERO_KEY, "not*base64*at*all", wv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid Base64");
        }
    }

    @Test
    void s6_unknownWriteVersion_refuses() {
        for (String wv : List.of("v3", "V2X", "2", "legacy")) {
            assertThatThrownBy(() -> new EncryptionService(ZERO_KEY, V2_KEY, wv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be v1 or v2");
        }
    }

    @Test
    void s7_allowedConfigurations_start() {
        assertThat(new EncryptionService(ZERO_KEY, null, "v1").writeVersion()).isEqualTo(EncryptionService.WriteVersion.V1);
        assertThat(new EncryptionService(ZERO_KEY, "", null).writeVersion()).isEqualTo(EncryptionService.WriteVersion.V1);
        assertThat(new EncryptionService(ZERO_KEY, V2_KEY, " V1 ").writeVersion()).isEqualTo(EncryptionService.WriteVersion.V1);
        assertThat(new EncryptionService(ZERO_KEY, V2_KEY, "V2").writeVersion()).isEqualTo(EncryptionService.WriteVersion.V2);
        // the legacy key's own rule is unchanged
        assertThatThrownBy(() -> new EncryptionService("AAAA", V2_KEY, "v1"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_ENCRYPTION_KEY must decode");
    }

    // ── messages ───────────────────────────────────────────────────────────────────────────

    @Test
    void m1_exceptionMessages_carryNoPlaintextCiphertextOrKey_andNoCause() {
        String plain = "PLAINTEXT-SENTINEL-4242";
        EncryptionService e = v2();
        String ct = e.encrypt(plain, "t|r");
        byte[] raw = Base64.getDecoder().decode(ct.substring(3));
        raw[20] ^= 0x01;
        String tampered = "v2:" + Base64.getEncoder().encodeToString(raw);
        String legacyKey7 = Base64.getEncoder().encodeToString(filled((byte) 7));

        List<Supplier<Throwable>> failures = List.of(
            () -> catchThrowable(() -> e.decrypt(ct, "t|WRONG")),
            () -> catchThrowable(() -> e.decrypt(tampered, "t|r")),
            () -> catchThrowable(() -> e.decrypt(ct)),
            () -> catchThrowable(() -> legacyOnly().decrypt(ct, "t|r")),
            () -> catchThrowable(() -> e.decrypt("v2:" + GOLDEN_CT)),
            () -> catchThrowable(() -> e.decrypt("v2:%%" + ct.substring(5))),
            () -> catchThrowable(() -> e.decrypt(ct.substring(3, 20))),
            () -> catchThrowable(() -> new EncryptionService(ZERO_KEY, legacyKey7.substring(0, 40) + "*=", "v2")),
            () -> catchThrowable(() -> new EncryptionService(legacyKey7, legacyKey7, "v2")),
            () -> catchThrowable(() -> new EncryptionService(ZERO_KEY, legacyKey7.substring(0, 24), "v2")),
            () -> catchThrowable(() -> new EncryptionService(ZERO_KEY, V2_KEY, plain)));

        for (Supplier<Throwable> f : failures) {
            Throwable t = f.get();
            assertThat(t).isInstanceOf(IllegalStateException.class);
            assertThat(t.getCause()).as(t.getMessage()).isNull();
            String msg = t.getMessage();
            assertThat(msg).doesNotContain(plain).doesNotContain(ct).doesNotContain(ct.substring(3, 15))
                .doesNotContain(tampered.substring(3, 15)).doesNotContain(GOLDEN_CT.substring(0, 12))
                .doesNotContain(V2_KEY.substring(0, 12)).doesNotContain(legacyKey7.substring(0, 12));
            assertThat(t.toString()).doesNotContain(plain);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private static byte[] filled(byte b) {
        byte[] k = new byte[32];
        Arrays.fill(k, b);
        return k;
    }

    /**
     * VERBATIM copy of the encrypt / decrypt bodies of the pre-key-ring EncryptionService
     * (origin/main d7b23d2a, src/main/java/com/traceability/security/EncryptionService.java:44-108),
     * Spring annotations removed. Do not "fix" it — it is the compatibility reference.
     */
    static final class PreKeyRingAlgorithm {
        private static final int GCM_IV_BYTES  = 12;
        private static final int GCM_TAG_BITS  = 128;

        private final SecretKey key;
        private final SecureRandom rng = new SecureRandom();

        PreKeyRingAlgorithm(String base64Key) {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            if (keyBytes.length != 32) {
                throw new IllegalStateException(
                    "APP_ENCRYPTION_KEY must decode to exactly 32 bytes (AES-256); got " + keyBytes.length);
            }
            this.key = new SecretKeySpec(keyBytes, "AES");
        }

        public String encrypt(String plaintext) {
            byte[] iv = new byte[GCM_IV_BYTES];
            rng.nextBytes(iv);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
                byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
                byte[] result = new byte[GCM_IV_BYTES + ciphertextWithTag.length];
                System.arraycopy(iv, 0, result, 0, GCM_IV_BYTES);
                System.arraycopy(ciphertextWithTag, 0, result, GCM_IV_BYTES, ciphertextWithTag.length);
                return Base64.getEncoder().encodeToString(result);
            } catch (Exception e) {
                throw new IllegalStateException("Encryption failed", e);
            }
        }

        public String decrypt(String encoded) {
            byte[] data = Base64.getDecoder().decode(encoded);
            byte[] iv         = Arrays.copyOfRange(data, 0, GCM_IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(data, GCM_IV_BYTES, data.length);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
                return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("Decryption failed", e);
            }
        }

        public String encrypt(String plaintext, String aad) {
            byte[] iv = new byte[GCM_IV_BYTES];
            rng.nextBytes(iv);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
                byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
                byte[] result = new byte[GCM_IV_BYTES + ciphertextWithTag.length];
                System.arraycopy(iv, 0, result, 0, GCM_IV_BYTES);
                System.arraycopy(ciphertextWithTag, 0, result, GCM_IV_BYTES, ciphertextWithTag.length);
                return Base64.getEncoder().encodeToString(result);
            } catch (Exception e) {
                throw new IllegalStateException("Encryption failed", e);
            }
        }

        public String decrypt(String encoded, String aad) {
            byte[] data = Base64.getDecoder().decode(encoded);
            byte[] iv         = Arrays.copyOfRange(data, 0, GCM_IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(data, GCM_IV_BYTES, data.length);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
                return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("Decryption failed", e);
            }
        }
    }
}
