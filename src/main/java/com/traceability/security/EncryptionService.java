package com.traceability.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryption for secrets persisted to the database (store access tokens,
 * courier API keys). The envelope key comes from APP_ENCRYPTION_KEY (base64, 32 bytes).
 *
 * Stored format: Base64( IV[12] || ciphertext || GCM-tag[16] )
 * A fresh 12-byte IV is generated per encrypt call; the tag is appended by the JCA
 * Cipher.doFinal() output automatically. Decryption strips the IV prefix.
 *
 * This class MUST NOT log or return decrypted values. Callers are responsible for
 * scrubbing plaintext from memory after use.
 */
@Service
public class EncryptionService {

    private static final int GCM_IV_BYTES  = 12;
    private static final int GCM_TAG_BITS  = 128;

    private final SecretKey key;
    private final SecureRandom rng = new SecureRandom();

    public EncryptionService(@Value("${app.encryption-key}") String base64Key) {
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
}
