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
     * A slim delivery item as returned by GET /api/v0/deliveries (list endpoint).
     *
     * List items are SLIM — businessReference is absent. Callers MUST use
     * fetchDelivery(apiKey, trackingNumber) to obtain the full delivery shape
     * (businessReference, shopifyOrderId, numberOfAttempts, raw JSON, etc.).
     */
    record SlimDelivery(String trackingNumber, int stateCode, String type) {}

    /**
     * Returns one page of slim delivery items from GET /api/v0/deliveries.
     *
     * Pagination: pageNumber (1-based) + pageSize (items per page).
     * Returns empty list when the page is past the end or the data array is absent/empty.
     * Defensive envelope handling: accepts both {data:[...]} and {data:{data:[...]}} shapes.
     *
     * state and type in each item may be plain scalars or nested objects — both are handled.
     *
     * Throws BostaTransientException on 5xx / network errors.
     * Throws BostaException on other non-retryable errors.
     */
    List<SlimDelivery> listDeliveriesPage(String apiKey, int pageNumber, int pageSize);

    /**
     * Validates an API key against the Bosta v0 deliveries list endpoint.
     * Returns "connected" on success (callers use this only for logging).
     * Throws ResponseStatusException(422) for 401/403 (invalid key — caller gets clean UI error).
     * Throws BostaTransientException on 5xx/network.
     * NOTE: /api/v0/business-profile is a phantom — it returns 404 on both v0 and v2.
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
