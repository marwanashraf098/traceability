package com.traceability.portal;

import com.traceability.security.EncryptionService;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Returns portal P2 — the ONE place return_requests.refund_details_encrypted is encrypted or
 * decrypted. AES-256-GCM with associated data "tenant_id|request_id" (EncryptionService's AAD
 * overload), so a ciphertext only opens on the row it was written for. Never logs plaintext.
 */
@Component
public class RefundDetailsCipher {

    private final EncryptionService encryption;

    public RefundDetailsCipher(EncryptionService encryption) {
        this.encryption = encryption;
    }

    static String aad(UUID tenantId, UUID requestId) {
        return tenantId + "|" + requestId;
    }

    public String encrypt(UUID tenantId, UUID requestId, String json) {
        return encryption.encrypt(json, aad(tenantId, requestId));
    }

    /** @throws IllegalStateException when the ciphertext wasn't written for this tenant + request. */
    public String decrypt(UUID tenantId, UUID requestId, String encrypted) {
        return encryption.decrypt(encrypted, aad(tenantId, requestId));
    }
}
