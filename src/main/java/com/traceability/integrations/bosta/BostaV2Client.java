package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Review mode (V130): no method here takes a tracking number (the creates send
 * businessReference / uniqueBusinessReference; read-backs go through
 * BostaHttpGateway.fetchDelivery), so the reserved simulated range ({@link SimulatedTracking})
 * is guarded there, not here. A simulated tenant never has a courier key to reach this class.
 *
 * Returns portal Step 4c-2 — the two Bosta v2 READS, kept apart from {@link BostaHttpGateway}
 * so the global {@code bosta.api-version} (v0) and every existing v0 call stay untouched.
 * Both URLs are built here as {base}/api/v2/…; the base is the same {@code BOSTA_BASE_URL}.
 *
 *   GET /api/v2/cities/getAllDistricts — Bosta's reference data, sent WITHOUT an
 *       Authorization header (the spec marks it {@code security: []}). A 401/403 means that is
 *       no longer true: {@link AuthRequiredException}, and the caller must not reach for a
 *       tenant's key.
 *   GET /api/v2/pickup-locations — the tenant's own pickup locations, with the tenant's
 *       decrypted API key as the raw Authorization header (same as the v0 client, no
 *       "Bearer "). 401/403 → {@link KeyRefusedException}.
 *
 * No Resilience4j retry: both are cheap reads a caller can repeat. Explicit timeouts (the v0
 * RestClient has none).
 *
 * Step 4c-3 — the ONE Bosta write this class makes, under MODE B AMENDMENT #2:
 *   POST /api/v2/deliveries?apiVersion=1 — create a type 25 CUSTOMER_RETURN_PICKUP, from an
 *       approved return request, with the tenant's raw key. {@link #createReturnPickup}. The
 *       customer's address is sent as pickupAddress (evidence: RR-B4BUBE, 2026-09-28).
 *       A type 30 exchange keeps the customer on dropOffAddress.
 *       Exactly ONE HTTP attempt: no retry decorator, no internal retry, its own timeouts
 *       (connect 5 s, read 20 s). A duplicate POST would book a second courier, so anything
 *       that might have reached Bosta is reported AMBIGUOUS and never re-sent automatically.
 *       Never logs the payload or the response body (customer PII) — request id, outcome and
 *       HTTP status only.
 *
 * Step 5c — the SECOND Bosta write, under MODE B AMENDMENT #3:
 *   POST /api/v2/deliveries?apiVersion=1 — create a type 30 EXCHANGE, only from an approved
 *       exchange request, with the tenant's raw key. {@link #createExchange}, called from ONE
 *       place (ReturnPickupBookingService's exchange booking). Its own payload builder
 *       ({@link #exchangePayload}) — the type 25 builder can never produce a type 30 and vice
 *       versa. Same endpoint, same create client (connect 5 s, read 20 s), same one-attempt
 *       transport and CREATED / NOT_CREATED / AMBIGUOUS mapping; no retry of any kind. Never
 *       logs the payload or the response body. Still no terminate, no edit, no other write.
 */
@Component
public class BostaV2Client {

    /** One district row of getAllDistricts, flattened with its city. Arabic = Bosta's "OtherName". */
    public record District(String districtId, String cityId, String cityName, String cityNameAr,
                           String zoneId, String zoneName, String zoneNameAr,
                           String districtName, String districtNameAr,
                           boolean pickupAvailable, boolean dropoffAvailable) {}

    public record PickupLocation(String id, String name, boolean isDefault, String cityName) {}

    /** getAllDistricts answered 401/403 — it needs auth now. */
    public static class AuthRequiredException extends BostaException {
        public AuthRequiredException(int status) { super("Bosta getAllDistricts answered " + status); }
    }

    /** pickup-locations answered 401/403 for the tenant's key. */
    public static class KeyRefusedException extends BostaException {
        public KeyRefusedException(int status) { super("Bosta refused the API key for pickup-locations (" + status + ")"); }
    }

    /** Everything the type 25 create sends (see {@link #returnPickupPayload}). */
    public record ReturnPickup(String uniqueBusinessReference, String businessReference, String businessLocationId,
                               String firstLine, String secondLine, String buildingNumber, String floor,
                               String apartment, String city, String districtId,
                               String receiverFirstName, String receiverLastName, String receiverPhone,
                               int itemsCount, String description, String returnNotes) {}

    /** Everything the type 30 create sends (see {@link #exchangePayload}). */
    public record Exchange(String uniqueBusinessReference, String businessReference, String businessLocationId,
                           String firstLine, String secondLine, String buildingNumber, String floor,
                           String apartment, String city, String districtId,
                           String receiverFirstName, String receiverLastName, String receiverPhone,
                           String outboundDescription, String returnDescription, String returnNotes) {}

    public enum CreateOutcome {
        /** Bosta answered 2xx with a tracking number. */
        CREATED,
        /** Definitely not created (4xx incl. 429, or the connection was never made) — safe to retry. */
        NOT_CREATED,
        /** May or may not have been created (5xx, timeout, reset, unreadable 2xx) — never re-send. */
        AMBIGUOUS
    }

    /**
     * {@code bostaErrorCode}: Bosta's own errorCode from a non-2xx body, when it sent one (for the
     * WARN log only — never the body itself).
     */
    public record CreateResult(CreateOutcome outcome, String deliveryId, String trackingNumber,
                               int httpStatus, String message, String bostaErrorCode) {
        public CreateResult(CreateOutcome outcome, String deliveryId, String trackingNumber, int httpStatus, String message) {
            this(outcome, deliveryId, trackingNumber, httpStatus, message, null);
        }
    }

    /** At most this much of Bosta's error fields is recorded in booking_error. */
    static final int BOSTA_ERROR_MAX = 300;
    /** A non-JSON body snippet is recorded only when short and free of long digit runs (phone numbers). */
    static final int NON_JSON_SNIPPET_MAX = 120;
    private static final java.util.regex.Pattern LONG_DIGITS = java.util.regex.Pattern.compile("\\d{7,}");

    private static final Logger log = LoggerFactory.getLogger(BostaV2Client.class);

    private final RestClient restClient;
    private final RestClient createClient;
    private final ObjectMapper mapper;
    private final String baseUrl;
    // Shared per-key Bosta budget (2026-10-03). Optional so hand-wired clients (tests) stay unlimited.
    private BostaRateLimiter limiter = BostaRateLimiter.unlimited();

    @Autowired(required = false)
    public void setRateLimiter(BostaRateLimiter limiter) {
        if (limiter != null) this.limiter = limiter;
    }

    public BostaV2Client(ObjectMapper mapper, String baseUrl) {
        this(mapper, baseUrl, Duration.ofSeconds(20));
    }

    @Autowired
    public BostaV2Client(ObjectMapper mapper, @Value("${bosta.base-url}") String baseUrl,
                         @Value("${bosta.create-read-timeout:20s}") Duration createReadTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        // The create call only: connect 5 s, read 20 s. Separate client so nothing else shares it.
        SimpleClientHttpRequestFactory createFactory = new SimpleClientHttpRequestFactory();
        createFactory.setConnectTimeout(Duration.ofSeconds(5));
        createFactory.setReadTimeout(createReadTimeout);
        this.createClient = RestClient.builder().requestFactory(createFactory).build();
        this.mapper     = mapper;
        this.baseUrl    = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * POST {base}/api/v2/deliveries?apiVersion=1 — ONE attempt, never retried here.
     * 2xx with data.trackingNumber → CREATED; 429 or other 4xx → NOT_CREATED (Bosta's message);
     * connection refused / unknown host (nothing sent) → NOT_CREATED; 5xx, timeout, reset or an
     * unreadable 2xx → AMBIGUOUS.
     */
    public CreateResult createReturnPickup(String apiKey, ReturnPickup p) {
        CreateResult result = postCreate(apiKey, returnPickupPayload(mapper, p).toString());
        log.info("Bosta createReturnPickup request={} outcome={} status={}",
            p.uniqueBusinessReference(), result.outcome(), result.httpStatus());
        warnIfRejected("createReturnPickup", p.uniqueBusinessReference(), result);
        return result;
    }

    /**
     * Step 5c — POST {base}/api/v2/deliveries?apiVersion=1 with a type 30 EXCHANGE body. ONE
     * attempt, never retried here; outcome mapping as {@link #createReturnPickup}.
     */
    public CreateResult createExchange(String apiKey, Exchange e) {
        CreateResult result = postCreate(apiKey, exchangePayload(mapper, e).toString());
        log.info("Bosta createExchange request={} outcome={} status={}",
            e.uniqueBusinessReference(), result.outcome(), result.httpStatus());
        warnIfRejected("createExchange", e.uniqueBusinessReference(), result);
        return result;
    }

    /** Non-2xx answer: request id, HTTP status and Bosta's errorCode only — never the payload or body. */
    private static void warnIfRejected(String call, String requestId, CreateResult r) {
        if (r.httpStatus() > 0 && (r.httpStatus() < 200 || r.httpStatus() >= 300)) {
            log.warn("Bosta {} rejected request={} status={} errorCode={}", call, requestId, r.httpStatus(), r.bostaErrorCode());
        }
    }

    /** The one-attempt create transport shared by both builders. Never logs the body. */
    private CreateResult postCreate(String apiKey, String json) {
        // Waits for the key's budget BEFORE the one attempt — never a second POST.
        try {
            limiter.acquire(apiKey, BostaRateLimiter.Priority.USER_FACING);
        } catch (BostaRateLimitException e) {
            // Nothing was sent: NOT_CREATED with status 0, like a connection that was never made.
            return new CreateResult(CreateOutcome.NOT_CREATED, null, null, 0,
                "Bosta rate limit for this account — nothing was sent, try again shortly");
        }
        CreateResult r = postCreateOnce(apiKey, json);
        if (r.httpStatus() == 429) limiter.onRateLimited(apiKey, 60, "v2 create");
        return r;
    }

    private CreateResult postCreateOnce(String apiKey, String json) {
        String url = baseUrl + "/api/v2/deliveries?apiVersion=1";
        CreateResult result;
        try {
            result = createClient.post().uri(url)
                .header("Authorization", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.getBytes(StandardCharsets.UTF_8))
                .exchange((req, resp) -> {
                    int status = resp.getStatusCode().value();
                    // A status arrived: an unreadable / missing body (e.g. an empty 5xx, which the JDK
                    // client can't open) is an empty body, never "no answer".
                    byte[] raw;
                    try { raw = resp.getBody().readAllBytes(); } catch (java.io.IOException e) { raw = new byte[0]; }
                    return interpret(status, raw);
                });
        } catch (ResourceAccessException e) {
            Throwable c = e.getCause();
            boolean neverSent = c instanceof ConnectException || c instanceof UnknownHostException
                || c instanceof NoRouteToHostException;
            result = neverSent
                ? new CreateResult(CreateOutcome.NOT_CREATED, null, null, 0, "Couldn't connect to Bosta.")
                : new CreateResult(CreateOutcome.AMBIGUOUS, null, null, 0,
                    "No answer from Bosta (timeout or dropped connection).");
        } catch (RuntimeException e) {
            result = new CreateResult(CreateOutcome.AMBIGUOUS, null, null, 0, "Unexpected error talking to Bosta.");
        }
        return result;
    }

    private CreateResult interpret(int status, byte[] raw) {
        JsonNode body = null;
        try { body = raw.length == 0 ? null : mapper.readTree(raw); } catch (Exception ignored) { /* unreadable */ }
        if (status >= 200 && status < 300) {
            JsonNode data = body == null ? null : body.path("data");
            String tracking = data == null ? null : text(data, "trackingNumber");
            if (tracking == null) {
                return new CreateResult(CreateOutcome.AMBIGUOUS, null, null, status,
                    "Bosta answered without a tracking number.");
            }
            return new CreateResult(CreateOutcome.CREATED, text(data, "_id"), tracking, status, null);
        }
        // Any non-2xx: Bosta's own error fields (message, errorCode, validation messages — nothing
        // else from the body) go into the message, so booking_error says why. Outcome mapping
        // unchanged: 429 / other 4xx → NOT_CREATED, 5xx → AMBIGUOUS (never re-sent).
        BostaError err = bostaError(body, raw);
        if (status == 429) {
            return new CreateResult(CreateOutcome.NOT_CREATED, null, null, status,
                "Bosta is rate-limiting requests. Try again in a few minutes." + err.suffix(false), err.code());
        }
        if (status >= 400 && status < 500) {
            String base = err.message() != null ? err.message() : "Bosta rejected the request (HTTP " + status + ").";
            return new CreateResult(CreateOutcome.NOT_CREATED, null, null, status,
                truncate(base, BOSTA_ERROR_MAX) + err.suffix(err.message() != null), err.code());
        }
        return new CreateResult(CreateOutcome.AMBIGUOUS, null, null, status,
            "Bosta answered with a server error (HTTP " + status + ")." + err.suffix(false), err.code());
    }

    /**
     * Bosta's error fields from a non-2xx body. {@code message}, {@code errorCode} and validation
     * messages (errors[] / details[] / validationErrors[] items' message or msg, or the string
     * itself; a string {@code error}) — nothing else. Not JSON → "non-JSON body", plus its first
     * 120 characters only when they hold no run of 7+ digits (a phone number could hide there).
     */
    record BostaError(String message, String code, List<String> validation, String nonJson) {
        /** " Bosta said: …" (or, when the base already is Bosta's message, just its code / validation), ≤ 300 chars. */
        String suffix(boolean messageAlreadyShown) {
            List<String> parts = new ArrayList<>();
            if (nonJson != null) parts.add(nonJson);
            if (!messageAlreadyShown && message != null) parts.add(message);
            parts.addAll(validation);
            String codePart = code == null ? "" : " (Bosta error " + code + ")";
            if (parts.isEmpty() && codePart.isEmpty()) return "";
            String detail = truncate(String.join("; ", parts) + codePart, BOSTA_ERROR_MAX).trim();
            return parts.isEmpty() ? " " + detail : " Bosta said: " + detail;
        }
    }

    static BostaError bostaError(JsonNode body, byte[] raw) {
        if (body == null || !body.isObject()) {
            String text = raw == null ? "" : new String(raw, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
            String snippet = text.length() > NON_JSON_SNIPPET_MAX ? text.substring(0, NON_JSON_SNIPPET_MAX) : text;
            String nonJson = snippet.isEmpty() || LONG_DIGITS.matcher(snippet).find()
                ? "non-JSON body" : "non-JSON body: " + snippet;
            return new BostaError(null, null, List.of(), nonJson);
        }
        String message = text(body, "message");
        JsonNode codeNode = body.path("errorCode");
        String code = codeNode.isMissingNode() || codeNode.isNull() || codeNode.asText().isBlank() ? null : codeNode.asText().trim();
        List<String> validation = new ArrayList<>();
        for (String field : List.of("errors", "details", "validationErrors")) {
            JsonNode v = body.path(field);
            if (v.isArray()) {
                for (JsonNode e : v) {
                    String m = e.isTextual() ? e.asText() : e.hasNonNull("message") ? e.get("message").asText()
                        : e.hasNonNull("msg") ? e.get("msg").asText() : null;
                    if (m != null && !m.isBlank()) validation.add(m.trim());
                }
            } else if (v.isTextual() && !v.asText().isBlank()) {
                validation.add(v.asText().trim());
            }
        }
        JsonNode error = body.path("error");
        if (error.isTextual() && !error.asText().isBlank()) validation.add(error.asText().trim());
        validation.remove(message);
        return new BostaError(message, code, validation, null);
    }

    /**
     * The exact type 25 create body. The CUSTOMER's address is sent as pickupAddress — that is
     * where Bosta keeps it on a CRP (every dashboard-made CRP stores the customer in pickupAddress
     * and the merchant in dropOffAddress), and sending it as dropOffAddress made Bosta fail with
     * HTTP 500 "Cannot read properties of undefined (reading 'city')" (RR-B4BUBE, 2026-09-28).
     * The merchant side comes from businessLocationId. Never dropOffAddress, returnAddress,
     * allowToOpenPackage or webhookUrl.
     */
    public static ObjectNode returnPickupPayload(ObjectMapper mapper, ReturnPickup p) {
        ObjectNode b = mapper.createObjectNode();
        b.put("type", 25);
        b.put("cod", 0);
        ObjectNode drop = b.putObject("pickupAddress");
        drop.put("firstLine", p.firstLine());
        putIfPresent(drop, "secondLine", p.secondLine());
        putIfPresent(drop, "buildingNumber", p.buildingNumber());
        putIfPresent(drop, "floor", p.floor());
        putIfPresent(drop, "apartment", p.apartment());
        drop.put("city", p.city());
        drop.put("districtId", p.districtId());
        b.put("businessLocationId", p.businessLocationId());
        ObjectNode receiver = b.putObject("receiver");
        receiver.put("firstName", p.receiverFirstName());
        putIfPresent(receiver, "lastName", p.receiverLastName());
        receiver.put("phone", p.receiverPhone());
        b.put("businessReference", p.businessReference());
        b.put("uniqueBusinessReference", p.uniqueBusinessReference());
        ObjectNode details = b.putObject("returnSpecs").putObject("packageDetails");
        details.put("itemsCount", p.itemsCount());
        details.put("description", p.description());
        b.put("returnNotes", p.returnNotes());
        return b;
    }

    /**
     * The exact type 30 create body: customer on dropOffAddress, the merchant's return location
     * as businessLocationId, one item each way (specs = the replacement going out, returnSpecs =
     * the item coming back), cod 0. Never pickupAddress, returnAddress, allowToOpenPackage,
     * webhookUrl, goodsInfo or productInfo.
     */
    public static ObjectNode exchangePayload(ObjectMapper mapper, Exchange e) {
        ObjectNode b = mapper.createObjectNode();
        b.put("type", 30);
        b.put("cod", 0);
        ObjectNode drop = b.putObject("dropOffAddress");
        drop.put("firstLine", e.firstLine());
        putIfPresent(drop, "secondLine", e.secondLine());
        putIfPresent(drop, "buildingNumber", e.buildingNumber());
        putIfPresent(drop, "floor", e.floor());
        putIfPresent(drop, "apartment", e.apartment());
        drop.put("city", e.city());
        drop.put("districtId", e.districtId());
        b.put("businessLocationId", e.businessLocationId());
        ObjectNode receiver = b.putObject("receiver");
        receiver.put("firstName", e.receiverFirstName());
        putIfPresent(receiver, "lastName", e.receiverLastName());
        receiver.put("phone", e.receiverPhone());
        b.put("businessReference", e.businessReference());
        b.put("uniqueBusinessReference", e.uniqueBusinessReference());
        ObjectNode out = b.putObject("specs").putObject("packageDetails");
        out.put("itemsCount", 1);
        out.put("description", e.outboundDescription());
        ObjectNode back = b.putObject("returnSpecs").putObject("packageDetails");
        back.put("itemsCount", 1);
        back.put("description", e.returnDescription());
        b.put("returnNotes", e.returnNotes());
        return b;
    }

    private static void putIfPresent(ObjectNode n, String field, String value) {
        if (value != null && !value.isBlank()) n.put(field, value);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    public List<District> fetchAllDistricts() {
        String url = baseUrl + "/api/v2/cities/getAllDistricts";
        try {
            String body = restClient.get().uri(url).retrieve().body(String.class);
            return parseAllDistricts(body == null ? null : mapper.readTree(body));
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) throw new AuthRequiredException(status);
            if (e.getStatusCode().is5xxServerError()) throw new BostaTransientException("Bosta 5xx on getAllDistricts", e);
            throw new BostaException("Bosta getAllDistricts error (" + status + ")", e);
        } catch (ResourceAccessException e) {
            throw new BostaTransientException("Network error on getAllDistricts", e);
        } catch (BostaException e) {
            throw e;
        } catch (Exception e) {
            throw new BostaException("Unreadable getAllDistricts response", e);
        }
    }

    /** A raw search answer for diagnostics: HTTP status (0 = no answer), parsed body or null, error text. */
    public record SearchResponse(int status, JsonNode body, String error) {}

    /**
     * POST {base}/api/v2/deliveries/search — the dashboard's paged delivery search, READ-ONLY (a
     * query sent as a POST body; it creates and changes nothing). Body exactly as the dashboard
     * sends it: {"stateCodes": [], "limit": …, "page": …, "sortBy": …}. The tenant's raw key as the
     * Authorization header, like every other v2 call here. Diagnostics only (BostaSearchProbe) until
     * discovery is switched over. Never retried; a 429 blocks the key in the shared limiter.
     */
    public SearchResponse searchDeliveries(String apiKey, int page, int limit, String sortBy) {
        try {
            limiter.acquire(apiKey, BostaRateLimiter.Priority.BACKGROUND);
        } catch (BostaRateLimitException e) {
            return new SearchResponse(0, null, "rate limited before sending (retry after " + e.getRetryAfterSeconds() + "s)");
        }
        String url = baseUrl + "/api/v2/deliveries/search";
        com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
        body.putArray("stateCodes");
        body.put("limit", limit);
        body.put("page", page);
        body.put("sortBy", sortBy);
        try {
            String resp = restClient.post().uri(url)
                .header("Authorization", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString().getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .body(String.class);
            return new SearchResponse(200, resp == null ? null : mapper.readTree(resp), null);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429) limiter.onRateLimited(apiKey, 60, "v2 deliveries/search probe");
            JsonNode errBody = null;
            try { errBody = mapper.readTree(e.getResponseBodyAsString()); } catch (Exception ignored) { /* not JSON */ }
            return new SearchResponse(status, errBody, "HTTP " + status);
        } catch (Exception e) {
            return new SearchResponse(0, null, e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    /**
     * One page of the v2 delivery search, for discovery (2026-10-03). Same request as
     * {@link #searchDeliveries} (READ-ONLY; shared rate limiter, BACKGROUND; never retried here).
     * Contract proven in prod by BostaSearchProbe (BROEK + Femine, 2026-10-03): HTTP 200,
     * {@code data.deliveries}, page / limit honoured, {@code count} always "0" (so the end of the list is
     * an empty or short page, never a total). Returns the raw items in Bosta's order.
     *
     * @throws BostaRateLimitException a 429 (the key is blocked in the limiter for its retry-after) or
     *         a limiter wait longer than its maximum
     * @throws BostaTransientException 5xx, timeout / IO, or a 2xx without data.deliveries
     * @throws BostaException any other 4xx (e.g. the key refused)
     */
    public List<JsonNode> searchDeliveriesPage(String apiKey, int page, int limit, String sortBy) {
        limiter.acquire(apiKey, BostaRateLimiter.Priority.BACKGROUND);
        String url = baseUrl + "/api/v2/deliveries/search";
        ObjectNode body = mapper.createObjectNode();
        body.putArray("stateCodes");
        body.put("limit", limit);
        body.put("page", page);
        body.put("sortBy", sortBy);
        String resp;
        try {
            resp = restClient.post().uri(url)
                .header("Authorization", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString().getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429) {
                long retryAfter = retryAfterSeconds(e);
                limiter.onRateLimited(apiKey, retryAfter, "v2 deliveries/search page " + page);
                throw new BostaRateLimitException(retryAfter);
            }
            if (status >= 500) throw new BostaTransientException("Bosta delivery search page " + page + " answered " + status);
            throw new BostaException("Bosta delivery search page " + page + " answered " + status);
        } catch (Exception e) {
            throw new BostaTransientException("Bosta delivery search page " + page + " failed: " + e.getClass().getSimpleName(), e);
        }
        JsonNode items;
        try {
            items = resp == null ? null : mapper.readTree(resp).path("data").path("deliveries");
        } catch (Exception e) {
            throw new BostaTransientException("Unreadable Bosta delivery search page " + page, e);
        }
        if (items == null || !items.isArray()) {
            throw new BostaTransientException("Bosta delivery search page " + page + " has no data.deliveries");
        }
        List<JsonNode> out = new ArrayList<>(items.size());
        items.forEach(out::add);
        return out;
    }

    /** Retry-After header, else the body's retryAfter, else 60 s. */
    private long retryAfterSeconds(RestClientResponseException e) {
        try {
            String h = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
            if (h != null) return Math.max(1, Long.parseLong(h.trim()));
        } catch (Exception ignored) { /* not a number */ }
        try {
            long b = mapper.readTree(e.getResponseBodyAsString()).path("retryAfter").asLong(0);
            if (b > 0) return b;
        } catch (Exception ignored) { /* not JSON */ }
        return 60;
    }

    public List<PickupLocation> listPickupLocations(String apiKey) {
        limiter.acquire(apiKey, BostaRateLimiter.Priority.USER_FACING);
        String url = baseUrl + "/api/v2/pickup-locations";
        try {
            String body = restClient.get().uri(url).header("Authorization", apiKey).retrieve().body(String.class);
            return parsePickupLocations(body == null ? null : mapper.readTree(body));
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) throw new KeyRefusedException(status);
            if (status == 429) {
                long retryAfter = retryAfterSeconds(e);
                limiter.onRateLimited(apiKey, retryAfter, "v2 pickup-locations");
                throw new BostaRateLimitException(retryAfter);
            }
            if (e.getStatusCode().is5xxServerError()) throw new BostaTransientException("Bosta 5xx on pickup-locations", e);
            throw new BostaException("Bosta pickup-locations error (" + status + ")", e);
        } catch (ResourceAccessException e) {
            throw new BostaTransientException("Network error on pickup-locations", e);
        } catch (BostaException e) {
            throw e;
        } catch (Exception e) {
            throw new BostaException("Unreadable pickup-locations response", e);
        }
    }

    /**
     * {success, data: [ {cityId, cityName, cityOtherName, pickupAvailability, districts: [
     * {zoneId, zoneName, zoneOtherName, districtId, districtName, districtOtherName,
     * pickupAvailability, dropOffAvailability} ]} ]}. {@code data.list} is accepted too.
     * A district is pickup-available only when both it and its city say so (a missing city
     * flag counts as true). Rows without a districtId or cityId are skipped.
     */
    static List<District> parseAllDistricts(JsonNode body) {
        List<District> out = new ArrayList<>();
        if (body == null) return out;
        JsonNode cities = body.path("data");
        if (cities.isObject()) cities = cities.path("list");
        if (!cities.isArray()) return out;
        for (JsonNode city : cities) {
            String cityId = text(city, "cityId");
            String cityName = text(city, "cityName");
            if (cityId == null || cityName == null) continue;
            boolean cityPickup = city.path("pickupAvailability").asBoolean(true);
            boolean cityDropoff = city.path("dropOffAvailability").asBoolean(true);
            for (JsonNode d : city.path("districts")) {
                String districtId = text(d, "districtId");
                String districtName = text(d, "districtName");
                if (districtId == null || districtName == null) continue;
                out.add(new District(districtId, cityId, cityName, text(city, "cityOtherName"),
                    text(d, "zoneId"), text(d, "zoneName"), text(d, "zoneOtherName"),
                    districtName, text(d, "districtOtherName"),
                    cityPickup && d.path("pickupAvailability").asBoolean(false),
                    cityDropoff && d.path("dropOffAvailability").asBoolean(false)));
            }
        }
        return out;
    }

    /** {success, data: {list: [ {_id, locationName, isDefault?, address: {city: {name}}} ], ...}}. */
    static List<PickupLocation> parsePickupLocations(JsonNode body) {
        List<PickupLocation> out = new ArrayList<>();
        if (body == null) return out;
        JsonNode list = body.path("data");
        if (list.isObject()) list = list.path("list");
        if (!list.isArray()) return out;
        for (JsonNode l : list) {
            String id = text(l, "_id");
            if (id == null) continue;
            String name = text(l, "locationName");
            out.add(new PickupLocation(id, name != null ? name : id, l.path("isDefault").asBoolean(false),
                text(l.path("address").path("city"), "name")));
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
