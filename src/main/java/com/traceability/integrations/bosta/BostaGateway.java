package com.traceability.integrations.bosta;

/**
 * Bosta courier API client interface.
 *
 * apiKey is passed per-call (not stored in the gateway) — the gateway is
 * shared across tenants; each tenant's encrypted key is loaded from the DB
 * and decrypted at call time.
 */
public interface BostaGateway {

    /**
     * Validates an API key by fetching the business profile.
     * Returns the business name on success.
     * Throws BostaException on 4xx (invalid key), BostaTransientException on 5xx/network.
     */
    String fetchBusinessProfile(String apiKey);

    /**
     * Fetches authoritative delivery state from Bosta.
     * Always used in verify-by-fetch pattern — never trust the webhook payload alone.
     * Returns null if the delivery is not found (404).
     * Throws BostaTransientException on network / 5xx (caller should retry).
     * Throws BostaException on other non-retryable errors.
     */
    BostaDelivery fetchDelivery(String apiKey, String trackingNumber);
}
