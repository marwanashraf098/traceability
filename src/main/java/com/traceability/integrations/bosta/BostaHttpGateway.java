package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/**
 * Bosta Admin API client.
 *
 * Auth: Authorization header set to the raw API key (no "Bearer " prefix).
 * API version is pinned from config (bosta.api-version, currently "v2").
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
    public String fetchBusinessProfile(String apiKey) {
        String url = baseUrl + "/api/" + apiVersion + "/business-profile";
        try {
            JsonNode body = Retry.decorateSupplier(retry, () ->
                restClient.get()
                    .uri(url)
                    .header("Authorization", apiKey)
                    .retrieve()
                    .body(JsonNode.class)
            ).get();
            if (body == null) throw new BostaException("Empty business profile response");
            JsonNode data = body.path("data");
            return data.path("businessName").asText(data.path("name").asText("unknown"));
        } catch (ResourceAccessException e) {
            throw new BostaTransientException("Network error fetching Bosta business profile", e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new BostaTransientException("Bosta 5xx on business profile", e);
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
            String tn   = data.path("trackingNumber").asText(trackingNumber);
            int code    = data.path("state").asInt(-1);
            String type = data.path("type").asText("SEND");
            int attempts = data.path("numberOfAttempts").asInt(0);
            String ref   = data.path("businessReference").asText(null);

            return new BostaDelivery(tn, code, type, attempts, ref, data);
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
}
