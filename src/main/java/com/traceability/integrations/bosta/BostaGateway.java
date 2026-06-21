package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

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

    /**
     * Requests AWB labels via Bosta mass-awb endpoint.
     *
     * trackingNumbers must be non-empty and ≤50 items per call (Bosta inline PDF limit).
     * Callers are responsible for batching at ≤50.
     *
     * Inline path: result.pdfBytes() is set, emailMessage is null.
     * Email path (Bosta went async): result.emailMessage() is set, pdfBytes is null.
     *
     * Throws BostaException on 4xx (e.g. invalid tracking numbers).
     * Throws BostaTransientException on 5xx / network errors.
     */
    AwbPrintResult printMassAwb(String apiKey, List<String> trackingNumbers,
                                 String awbFormat, String lang);

    /**
     * Schedules a Bosta pickup (location + date — tracking numbers are NOT attached).
     *
     * Returns Bosta provider pickup ID (_id) on success.
     * Throws BostaPickupAlreadyExistsException for codes 1078 / 2024–2027.
     * Throws BostaPickupDateException for codes 1080 / 1081 / 1083 / 2022.
     * Throws BostaTransientException on 5xx / network errors.
     * Throws BostaException on other non-retryable errors.
     */
    String createPickup(String apiKey, String scheduledDate, String businessLocationId,
                        JsonNode contactPerson, int numberOfParcels);
}
