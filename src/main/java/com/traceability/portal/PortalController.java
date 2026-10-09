package com.traceability.portal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public returns portal — unauthenticated (SecurityConfig permitAll /api/v1/portal/**),
 * rate-limited at nginx (zone "portal": 30 requests/minute per client IP, burst 15 — deploy/nginx.conf)
 * and, for lookup, in the app: per order key (5 failures / 60 min) and per client IP (P1: 20 failures
 * / 60 min across order keys, the IP stored only as an HMAC).
 *
 * Unknown or disabled slug → 404 with no body. A lookup failure of ANY kind → 404 with the
 * one generic message below, so a caller can never tell which check failed. Nothing about
 * the order number, phone or outcome is logged here.
 */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalController {

    static final String NOT_FOUND_MESSAGE = "We couldn't find a returnable order with those details.";
    static final String THROTTLED_MESSAGE = "Too many attempts. Please try again later.";

    private final PortalService portal;
    private final ObjectMapper  mapper;

    public PortalController(PortalService portal, ObjectMapper mapper) {
        this.portal = portal;
        this.mapper = mapper;
    }

    @GetMapping("/{slug}/config")
    public ResponseEntity<Map<String, Object>> config(@PathVariable String slug) {
        return portal.config(slug)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    static final String UNAUTHORIZED_MESSAGE = "Your session has expired. Please look up your order again.";
    static final String INVALID_MESSAGE      = "We couldn't accept this return request. Please start again.";
    static final String CONFLICT_MESSAGE     = "Some items are no longer available. Please start again.";

    /**
     * Step 4b — customer submits a return request. Auth = the lookup token as a Bearer header
     * (no cookies). 401 / 400 / 409 each carry one generic message; the response on success is
     * { reference, status } only — no PII.
     */
    @PostMapping("/{slug}/requests")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable String slug,
                                                      @RequestHeader(value = "Authorization", required = false) String authorization,
                                                      @RequestBody(required = false) String rawBody) {
        String token = authorization != null && authorization.startsWith("Bearer ")
            ? authorization.substring("Bearer ".length()).trim() : null;
        // Parsed here, not by Spring: a malformed body must get the same generic 400, and the
        // global catch-all handler would otherwise turn a deserialization error into a 500.
        PortalService.SubmitRequest req = parseSubmitBody(rawBody);
        return portal.submit(slug, token, req)
            .map(r -> switch (r.outcome()) {
                case CREATED      -> ResponseEntity.status(HttpStatus.CREATED).body(r.body());
                case UNAUTHORIZED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                         .body(Map.<String, Object>of("message", UNAUTHORIZED_MESSAGE));
                case INVALID      -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                         .body(Map.<String, Object>of("message", INVALID_MESSAGE));
                case CONFLICT     -> ResponseEntity.status(HttpStatus.CONFLICT)
                                         .body(Map.<String, Object>of("message", CONFLICT_MESSAGE));
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Null when unparseable — PortalService then answers INVALID (after the token check). */
    private PortalService.SubmitRequest parseSubmitBody(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode n = mapper.readTree(raw);
            List<PortalService.SubmitLine> lines = new ArrayList<>();
            for (JsonNode l : n.path("lines")) {
                // Step 6a: an untracked order line is referenced by orderItemId (variantId optional).
                UUID orderItemId = l.hasNonNull("orderItemId") ? UUID.fromString(l.get("orderItemId").asText()) : null;
                UUID variantId = orderItemId != null && !l.hasNonNull("variantId") ? null
                    : UUID.fromString(l.path("variantId").asText());
                Integer quantity = l.path("quantity").isInt() ? l.path("quantity").asInt() : null;
                lines.add(new PortalService.SubmitLine(variantId, quantity, l.path("reasonCode").asText(null), orderItemId));
            }
            String email = n.hasNonNull("email") ? n.get("email").asText() : null;
            String note  = n.hasNonNull("note")  ? n.get("note").asText()  : null;
            String districtId = n.hasNonNull("districtId") ? n.get("districtId").asText() : null;
            // Step 5b: an exchange carries mode, the wanted variant and the refund-fallback choice.
            String mode = n.hasNonNull("mode") ? n.get("mode").asText() : null;
            UUID replacement = n.hasNonNull("replacementVariantId")
                ? UUID.fromString(n.get("replacementVariantId").asText()) : null;
            Boolean fallback = n.hasNonNull("refundFallbackOk") ? n.get("refundFallbackOk").asBoolean() : null;
            // V117: the pickup address — 'order' (default: the delivery address) or 'custom'.
            String addressSource = n.hasNonNull("addressSource") ? n.get("addressSource").asText() : null;
            PortalService.CustomAddress custom = "custom".equals(addressSource)
                ? new PortalService.CustomAddress(text(n, "cityId"), text(n, "districtId"), text(n, "firstLine"),
                    text(n, "secondLine"), text(n, "buildingNumber"), text(n, "floor"), text(n, "apartment"))
                : null;
            return new PortalService.SubmitRequest(lines, email, note, districtId, mode, replacement, fallback,
                addressSource, custom);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    /**
     * V117 — a city's districts for a different pickup address. Auth = the lookup token as a
     * Bearer header (401 with the generic session message otherwise). mode=exchange keeps only
     * districts Bosta can also deliver to. Reference data only — never a street address.
     */
    @GetMapping("/{slug}/districts")
    public ResponseEntity<Map<String, Object>> districts(@PathVariable String slug,
                                                         @RequestHeader(value = "Authorization", required = false) String authorization,
                                                         @RequestParam(value = "cityId", required = false) String cityId,
                                                         @RequestParam(value = "mode", required = false) String mode) {
        String token = authorization != null && authorization.startsWith("Bearer ")
            ? authorization.substring("Bearer ".length()).trim() : null;
        return portal.districts(slug, token, cityId, "exchange".equals(mode))
            .map(r -> switch (r.outcome()) {
                case OK           -> ResponseEntity.ok(r.body());
                case UNAUTHORIZED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                         .body(Map.<String, Object>of("message", UNAUTHORIZED_MESSAGE));
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record LookupRequest(String orderNumber, String phone) {}

    @PostMapping("/{slug}/lookup")
    public ResponseEntity<Map<String, Object>> lookup(@PathVariable String slug,
                                                      @RequestBody(required = false) LookupRequest req,
                                                      jakarta.servlet.http.HttpServletRequest http) {
        String orderNumber = req == null ? null : req.orderNumber();
        String phone       = req == null ? null : req.phone();
        // The real client IP: nginx overwrites X-Forwarded-For and Tomcat's RemoteIpValve trusts it
        // only from the internal proxy (server.forward-headers-strategy: native).
        return portal.lookup(slug, orderNumber, phone, http.getRemoteAddr())
            .map(r -> switch (r.outcome()) {
                case SUCCESS   -> ResponseEntity.ok(r.body());
                case THROTTLED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                      .body(Map.<String, Object>of("message", THROTTLED_MESSAGE));
                case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                                      .body(Map.<String, Object>of("message", NOT_FOUND_MESSAGE));
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** A versioned URL whose ?v= matches the current logo never changes content: cache it for good. */
    static final org.springframework.http.CacheControl LOGO_IMMUTABLE =
        org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().immutable();
    /** No ?v=, or a stale one (the logo was replaced): revalidate hourly against the ETag. */
    static final org.springframework.http.CacheControl LOGO_REVALIDATE =
        org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic();

    /**
     * P1 — the merchant's uploaded logo, served by this app so the portal stays under CSP
     * img-src 'self'. ETag = the bytes' SHA-256; a matching If-None-Match gets 304 without the
     * bytes being read. /config's logoUrl carries ?v= (the first 12 hex digits of that SHA-256):
     * when it matches the current logo the answer is cacheable for a year (immutable); a missing
     * or stale ?v= gets the current bytes with the 1-hour + ETag policy. 404 (no body) for an
     * unknown or disabled slug or no uploaded logo.
     */
    @GetMapping("/{slug}/logo")
    public ResponseEntity<byte[]> logo(@PathVariable String slug,
                                       @RequestParam(value = "v", required = false) String version,
                                       @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        return portal.logo(slug, ifNoneMatch, version)
            .map(l -> {
                ResponseEntity.BodyBuilder b = ResponseEntity.status(l.bytes() == null ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                    .eTag(l.etag())
                    .cacheControl(l.current() ? LOGO_IMMUTABLE : LOGO_REVALIDATE)
                    .header("X-Content-Type-Options", "nosniff");
                return l.bytes() == null ? b.<byte[]>build()
                    : b.contentType(org.springframework.http.MediaType.parseMediaType(l.contentType())).body(l.bytes());
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
