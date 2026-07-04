package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bosta Admin API client.
 *
 * Auth: Authorization header set to the raw API key (no "Bearer " prefix).
 * API version is pinned from config (bosta.api-version, currently "v0").
 * Base URL is configurable: prod = https://app.bosta.co, staging = https://stg-app.bosta.co.
 *
 * Retry: Resilience4j, 3 attempts, 1-second wait, retries on network errors (5xx
 * errors throw BostaTransientException immediately so callers can act on them).
 *
 * Delivery response shape (§8):
 *   trackingNumber — String (camelCase, always String even if Bosta sends a number)
 *   state          — numeric int
 *   type           — "SEND" or "RTO"
 *   numberOfAttempts, businessReference
 */
@Service
class BostaHttpGateway implements BostaGateway {

    private static final Logger log = LoggerFactory.getLogger(BostaHttpGateway.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiVersion;
    private final Retry retry;

    BostaHttpGateway(RestClient.Builder builder, ObjectMapper mapper,
                     @Value("${bosta.base-url}") String baseUrl,
                     @Value("${bosta.api-version}") String apiVersion) {
        this.restClient = builder.build();
        this.mapper     = mapper;
        this.baseUrl    = baseUrl;
        this.apiVersion = apiVersion;
        this.retry = Retry.of("bosta-http", RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofSeconds(1))
            .retryExceptions(ResourceAccessException.class)
            .ignoreExceptions(BostaException.class)
            .build());
    }

    @Override
    public List<SlimDelivery> listDeliveriesPage(String apiKey, int pageNumber, int pageSize) {
        String url = baseUrl + "/api/" + apiVersion + "/deliveries?pageNumber=" + pageNumber + "&pageSize=" + pageSize;
        try {
            JsonNode body = Retry.decorateSupplier(retry, () ->
                restClient.get()
                    .uri(url)
                    .header("Authorization", apiKey)
                    .retrieve()
                    .body(JsonNode.class)
            ).get();

            if (body == null) return List.of();

            // Real Bosta v0 list envelope: {deliveries:[...], count:N}
            // Defensive fallback: {data:[...]} or {data:{data:[...]}}
            JsonNode dataNode = body.path("deliveries");
            if (!dataNode.isArray()) {
                dataNode = body.path("data");
                if (dataNode.isObject()) dataNode = dataNode.path("data");
            }
            if (!dataNode.isArray() || dataNode.isEmpty()) return List.of();

            List<SlimDelivery> result = new ArrayList<>();
            for (JsonNode item : dataNode) {
                String tn = item.path("trackingNumber").asText(null);
                if (tn == null || tn.isBlank()) continue;

                // state is object {code:N, value:"..."} in live v0 — handles flat int defensively
                JsonNode stateNode = item.path("state");
                int stateCode = stateNode.isObject()
                    ? stateNode.path("code").asInt(-1)
                    : stateNode.asInt(-1);

                // type is object {code:N, value:"Send"/"RTO"} in live v0
                // normalize to uppercase so BostaStateMapper key lookup (e.g. "41:SEND") works
                JsonNode typeNode = item.path("type");
                String type = typeNode.isObject()
                    ? typeNode.path("value").asText("SEND").toUpperCase(Locale.ROOT)
                    : typeNode.asText("SEND").toUpperCase(Locale.ROOT);

                result.add(new SlimDelivery(tn, stateCode, type));
            }
            return result;
        } catch (ResourceAccessException e) {
            throw new BostaTransientException("Network error listing deliveries page " + pageNumber, e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new BostaTransientException("Bosta 5xx listing deliveries page " + pageNumber, e);
            }
            throw new BostaException(
                "Bosta list deliveries error (" + e.getStatusCode() + "): " + e.getMessage(), e);
        }
    }

    @Override
    public String fetchBusinessProfile(String apiKey) {
        // /api/v0/business-profile and /api/v2/business-profile both return 404 — phantom endpoint.
        // Use the deliveries list instead: same base path as the confirmed-working fetchDelivery,
        // page-size=1 to minimise payload. 200 = valid key; 401/403 = bad key → 422 to caller.
        String url = baseUrl + "/api/" + apiVersion + "/deliveries?pageNumber=1&pageSize=1";
        try {
            Retry.decorateSupplier(retry, () ->
                restClient.get()
                    .uri(url)
                    .header("Authorization", apiKey)
                    .retrieve()
                    .body(JsonNode.class)
            ).get();
            return "connected";
        } catch (ResourceAccessException e) {
            throw new BostaTransientException("Network error validating Bosta API key", e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Invalid Bosta API key");
            }
            if (e.getStatusCode().is5xxServerError()) {
                throw new BostaTransientException("Bosta 5xx validating API key", e);
            }
            throw new BostaException("Bosta API error (" + e.getStatusCode() + "): " + e.getMessage(), e);
        }
    }

    @Override
    public BostaDelivery fetchDelivery(String apiKey, String trackingNumber) {
        String url = baseUrl + "/api/" + apiVersion + "/deliveries/" + trackingNumber;
        try {
            JsonNode body = Retry.decorateSupplier(retry, () ->
                restClient.get()
                    .uri(url)
                    .header("Authorization", apiKey)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        // 404 and other 4xx should not be retried
                    })
                    .body(JsonNode.class)
            ).get();

            if (body == null) return null;

            JsonNode data = body.has("data") ? body.get("data") : body;

            // trackingNumber may arrive as number or string — always use asText()
            String tn = data.path("trackingNumber").asText(trackingNumber);

            // state is object {code:N, value:"..."} in live v0 — handles legacy flat int defensively
            JsonNode stateNode = data.path("state");
            int code = stateNode.isObject() ? stateNode.path("code").asInt(-1) : stateNode.asInt(-1);

            // type is object {code:N, value:"Send"/"RTO"} in live v0
            // normalize to uppercase so BostaStateMapper key lookup (e.g. "41:SEND") works
            JsonNode typeNode = data.path("type");
            String type = typeNode.isObject()
                ? typeNode.path("value").asText("SEND").toUpperCase(Locale.ROOT)
                : typeNode.asText("SEND").toUpperCase(Locale.ROOT);

            int attempts = data.path("numberOfAttempts").asInt(0);
            String ref   = data.path("businessReference").asText(null);

            // shopifyInfo.orderId (real v0 shape) — fall back to legacy top-level shopifyOrderId
            String shopifyOrderId = data.path("shopifyInfo").path("orderId").asText(null);
            if (shopifyOrderId == null || shopifyOrderId.isBlank()) {
                shopifyOrderId = data.path("shopifyOrderId").asText(null);
            }
            if (shopifyOrderId != null && shopifyOrderId.isBlank()) shopifyOrderId = null;

            return new BostaDelivery(tn, code, type, attempts, ref, shopifyOrderId, data);
        } catch (ResourceAccessException e) {
            throw new BostaTransientException("Network error fetching delivery " + trackingNumber, e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return null;
            if (e.getStatusCode().is5xxServerError()) {
                throw new BostaTransientException("Bosta 5xx fetching delivery " + trackingNumber, e);
            }
            throw new BostaException("Bosta API error (" + e.getStatusCode() + "): " + e.getMessage(), e);
        }
    }

    /**
     * POST /api/v0/deliveries/mass-awb
     * Payload: {trackingNumbers: "comma,separated", requestedAwbType: A4|A6, lang: ar|en}
     *
     * Inline response shape (live API v0): {"success":true,"data":"<base64>"}
     * Inline response shape (legacy/documented): {"success":true,"data":{"pdf":"<base64>"}}
     * Email path shape: {"success":true,"message":"AWB has been exported to your email"}
     */
    @Override
    public AwbPrintResult printMassAwb(String apiKey, List<String> trackingNumbers,
                                        String awbFormat, String lang) {
        String url = baseUrl + "/api/" + apiVersion + "/deliveries/mass-awb";
        Map<String, String> body = Map.of(
            "trackingNumbers",  String.join(",", trackingNumbers),
            "requestedAwbType", awbFormat,
            "lang",             lang
        );
        try {
            JsonNode resp = Retry.decorateSupplier(retry, () ->
                restClient.post()
                    .uri(url)
                    .header("Authorization", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class)
            ).get();

            if (resp == null) throw new BostaException("Empty mass-awb response");

            JsonNode dataNode = resp.path("data");
            if (!dataNode.isMissingNode() && !dataNode.isNull()) {
                // Live API (v0+): data is the base64 string directly
                if (dataNode.isTextual()) {
                    String pdfBase64 = dataNode.asText();
                    if (!pdfBase64.isBlank()) {
                        return new AwbPrintResult(Base64.getDecoder().decode(pdfBase64), null);
                    }
                }
                // Legacy/documented shape: data.pdf
                String pdfBase64 = dataNode.path("pdf").asText(null);
                if (pdfBase64 != null && !pdfBase64.isBlank()) {
                    return new AwbPrintResult(Base64.getDecoder().decode(pdfBase64), null);
                }
            }

            // Bosta returned without a PDF — email-path or unexpected shape
            String msg = resp.path("message").asText(
                dataNode.isMissingNode() ? "AWB exported to email" : dataNode.path("message").asText("AWB exported to email"));
            log.info("mass-awb email-path response for {} trackings: {}", trackingNumbers.size(), msg);
            return new AwbPrintResult(null, msg);

        } catch (ResourceAccessException e) {
            throw new BostaTransientException("Network error on mass-awb", e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new BostaTransientException("Bosta 5xx on mass-awb", e);
            }
            throw new BostaException("Bosta mass-awb error (" + e.getStatusCode() + "): " +
                e.getResponseBodyAsString(), e);
        }
    }

    /**
     * POST /api/v0/pickups
     * Payload: {scheduledDate, businessLocationId, contactPerson, numberOfParcels, packageType:"Normal"}
     *
     * Success response: {"success":true,"data":{"_id":"BOSTA_PICKUP_ID",...}}
     * Error response: {"success":false,"code":1078,"message":"..."}
     */
    @Override
    public String createPickup(String apiKey, String scheduledDate, String businessLocationId,
                                JsonNode contactPerson, int numberOfParcels) {
        String url = baseUrl + "/api/" + apiVersion + "/pickups";

        ObjectNode body = mapper.createObjectNode();
        body.put("scheduledDate", scheduledDate);
        body.put("businessLocationId", businessLocationId);
        if (contactPerson != null && !contactPerson.isNull()) {
            body.set("contactPerson", contactPerson);
        }
        body.put("numberOfParcels", numberOfParcels);
        body.put("packageType", "Normal");

        try {
            JsonNode resp = restClient.post()
                .uri(url)
                .header("Authorization", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, respObj) -> {
                    // suppress to handle error body manually
                })
                .body(JsonNode.class);

            if (resp == null) throw new BostaException("Empty createPickup response");

            // Check for error code in a 200 success-false body (Bosta sometimes does this)
            if (!resp.path("success").asBoolean(true)) {
                handlePickupErrorBody(resp);
            }

            String pickupId = resp.path("data").path("_id").asText(null);
            if (pickupId == null || pickupId.isBlank()) {
                throw new BostaException("No pickup _id in createPickup response: " + resp);
            }
            return pickupId;

        } catch (BostaException e) {
            throw e; // already typed — rethrow as-is
        } catch (ResourceAccessException e) {
            throw new BostaTransientException("Network error on createPickup", e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new BostaTransientException("Bosta 5xx on createPickup", e);
            }
            // Parse error body for Bosta-specific error codes
            try {
                JsonNode err = mapper.readTree(e.getResponseBodyAsString());
                handlePickupErrorBody(err);
            } catch (BostaException be) {
                throw be;
            } catch (Exception ignored) { /* fall through */ }
            throw new BostaException("Bosta createPickup error (" + e.getStatusCode() + "): " +
                e.getResponseBodyAsString(), e);
        }
    }

    private void handlePickupErrorBody(JsonNode err) {
        int code    = err.path("code").asInt(-1);
        String msg  = err.path("message").asText("Unknown Bosta pickup error");
        if (code == 1078 || (code >= 2024 && code <= 2027)) {
            throw new BostaPickupAlreadyExistsException(code, msg);
        }
        if (code == 1080 || code == 1081 || code == 1083 || code == 2022) {
            throw new BostaPickupDateException(code, msg);
        }
    }
}
