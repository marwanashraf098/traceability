package com.traceability.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * AES-256-GCM encryption for secrets persisted to the database (store access tokens,
 * courier API keys, refund details). Key ring (K1, 2026-11-28), two versions:
 *
 * <ul>
 *   <li><b>v1 (legacy)</b> — key {@code APP_ENCRYPTION_KEY}; stored as
 *       {@code Base64( IV[12] || ciphertext || GCM-tag[16] )}, no prefix — byte-for-byte the
 *       format this class wrote before the key ring.</li>
 *   <li><b>v2</b> — key {@code APP_ENCRYPTION_KEY_V2}; stored as
 *       {@code "v2:" + Base64( IV[12] || ciphertext || GCM-tag[16] )}.</li>
 * </ul>
 *
 * Decryption dispatches on the prefix, never by trial: a {@code "v2:"} value opens ONLY with the
 * V2 key (a tampered v2 value fails — it never falls back to the legacy key); anything else opens
 * ONLY with the legacy key. ':' is not in the Base64 alphabet, so the two can't be confused.
 * Encryption uses {@code APP_ENCRYPTION_WRITE_VERSION} (v1 | v2, default v1). The AAD overloads
 * behave the same for both versions.
 *
 * Startup refuses (IllegalStateException) when: the write version is v2 and V2 is missing; V2 is
 * set and is not Base64, not exactly 32 bytes, all zero bytes, or equal to the legacy key; the
 * write version is neither v1 nor v2.
 *
 * This class MUST NOT log or return decrypted values, and its exception messages never carry
 * plaintext, ciphertext or key material: no cause is chained (a Base64 / JCA message can quote
 * input bytes) — only the cause's class name is kept. Every failure message starts with
 * "Encryption failed" / "Decryption failed".
 */
@Service
public class EncryptionService {

    private static final Logger log = LoggerFactory.getLogger(EncryptionService.class);

    static final String V2_PREFIX = "v2:";

    private static final int GCM_IV_BYTES  = 12;
    private static final int GCM_TAG_BITS  = 128;
    private static final int GCM_TAG_BYTES = GCM_TAG_BITS / 8;
    private static final int KEY_BYTES     = 32;

    public enum WriteVersion { V1, V2 }

    private final SecretKey legacyKey;
    private final SecretKey v2Key;          // null when APP_ENCRYPTION_KEY_V2 is not set
    private final WriteVersion writeVersion;
    private final SecureRandom rng = new SecureRandom();

    public EncryptionService(@Value("${app.encryption-key}") String base64Key,
                             @Value("${app.encryption-key-v2:}") String base64KeyV2,
                             @Value("${app.encryption-write-version:v1}") String writeVersion) {
        byte[] legacyBytes = Base64.getDecoder().decode(base64Key);
        if (legacyBytes.length != KEY_BYTES) {
            throw new IllegalStateException(
                "APP_ENCRYPTION_KEY must decode to exactly 32 bytes (AES-256); got " + legacyBytes.length);
        }
        this.legacyKey = new SecretKeySpec(legacyBytes, "AES");
        this.writeVersion = parseWriteVersion(writeVersion);

        if (base64KeyV2 == null || base64KeyV2.isBlank()) {
            if (this.writeVersion == WriteVersion.V2) {
                throw new IllegalStateException(
                    "APP_ENCRYPTION_KEY_V2 is required when APP_ENCRYPTION_WRITE_VERSION=v2 — refusing to start");
            }
            this.v2Key = null;
        } else {
            byte[] v2Bytes;
            try {
                v2Bytes = Base64.getDecoder().decode(base64KeyV2.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("APP_ENCRYPTION_KEY_V2 is not valid Base64 — refusing to start");
            }
            if (v2Bytes.length != KEY_BYTES) {
                throw new IllegalStateException(
                    "APP_ENCRYPTION_KEY_V2 must decode to exactly 32 bytes (AES-256); got " + v2Bytes.length
                        + " — refusing to start");
            }
            if (allZero(v2Bytes)) {
                throw new IllegalStateException("APP_ENCRYPTION_KEY_V2 is all zero bytes — refusing to start");
            }
            if (MessageDigest.isEqual(v2Bytes, legacyBytes)) {
                throw new IllegalStateException(
                    "APP_ENCRYPTION_KEY_V2 equals APP_ENCRYPTION_KEY — refusing to start");
            }
            this.v2Key = new SecretKeySpec(v2Bytes, "AES");
        }
        log.info("Encryption key ring: write version {}, V2 key configured: {}",
            this.writeVersion.name().toLowerCase(Locale.ROOT), v2Key != null ? "yes" : "no");
    }

    private static WriteVersion parseWriteVersion(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "", "v1" -> WriteVersion.V1;
            case "v2" -> WriteVersion.V2;
            default -> throw new IllegalStateException(
                "APP_ENCRYPTION_WRITE_VERSION must be v1 or v2 — refusing to start");
        };
    }

    private static boolean allZero(byte[] b) {
        int acc = 0;
        for (byte x : b) acc |= x;
        return acc == 0;
    }

    /** The version {@link #encrypt} writes (diagnostics / tests; never key material). */
    public WriteVersion writeVersion() {
        return writeVersion;
    }

    public String encrypt(String plaintext) {
        return seal(plaintext, null);
    }

    public String decrypt(String encoded) {
        return open(encoded, null);
    }

    /**
     * P2 (V160) — AES-256-GCM with associated data: the ciphertext only decrypts with the SAME
     * {@code aad} (e.g. "tenant_id|request_id"), so one copied onto another row fails. Same stored
     * format as {@link #encrypt(String)}. Used ONLY for return_requests.refund_details_encrypted.
     */
    public String encrypt(String plaintext, String aad) {
        if (aad == null) throw new IllegalStateException("Encryption failed (no aad)");
        return seal(plaintext, aad.getBytes(StandardCharsets.UTF_8));
    }

    /** The {@link #encrypt(String, String)} counterpart; fails (IllegalStateException) when {@code aad} differs. */
    public String decrypt(String encoded, String aad) {
        if (aad == null) throw new IllegalStateException("Decryption failed (no aad)");
        return open(encoded, aad.getBytes(StandardCharsets.UTF_8));
    }

    private String seal(String plaintext, byte[] aad) {
        Objects.requireNonNull(plaintext, "plaintext");
        boolean v2 = writeVersion == WriteVersion.V2;
        SecretKey key = v2 ? v2Key : legacyKey;
        byte[] iv = new byte[GCM_IV_BYTES];
        rng.nextBytes(iv);
        byte[] ciphertextWithTag;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null) cipher.updateAAD(aad);
            ciphertextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed (" + e.getClass().getSimpleName() + ")");
        }
        byte[] result = new byte[GCM_IV_BYTES + ciphertextWithTag.length];
        System.arraycopy(iv, 0, result, 0, GCM_IV_BYTES);
        System.arraycopy(ciphertextWithTag, 0, result, GCM_IV_BYTES, ciphertextWithTag.length);
        String body = Base64.getEncoder().encodeToString(result);
        return v2 ? V2_PREFIX + body : body;
    }

    private String open(String encoded, byte[] aad) {
        Objects.requireNonNull(encoded, "encoded");
        SecretKey key;
        String body;
        if (encoded.startsWith(V2_PREFIX)) {
            if (v2Key == null) {
                throw new IllegalStateException("Decryption failed (v2 value, no V2 key configured)");
            }
            key = v2Key;
            body = encoded.substring(V2_PREFIX.length());
        } else {
            key = legacyKey;
            body = encoded;
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed (not Base64)");
        }
        if (data.length < GCM_IV_BYTES + GCM_TAG_BYTES) {
            throw new IllegalStateException("Decryption failed (value too short)");
        }
        try {
            byte[] iv         = Arrays.copyOfRange(data, 0, GCM_IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(data, GCM_IV_BYTES, data.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null) cipher.updateAAD(aad);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed (" + e.getClass().getSimpleName() + ")");
        }
    }
}
