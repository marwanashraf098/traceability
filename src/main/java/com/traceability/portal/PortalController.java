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
 * rate-limited at nginx (zone "portal", 10r/m per client IP) and per order key in the app.
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
                UUID variantId = UUID.fromString(l.path("variantId").asText());
                Integer quantity = l.path("quantity").isInt() ? l.path("quantity").asInt() : null;
                lines.add(new PortalService.SubmitLine(variantId, quantity, l.path("reasonCode").asText(null)));
            }
            String email = n.hasNonNull("email") ? n.get("email").asText() : null;
            String note  = n.hasNonNull("note")  ? n.get("note").asText()  : null;
            return new PortalService.SubmitRequest(lines, email, note);
        } catch (Exception e) {
            return null;
        }
    }

    public record LookupRequest(String orderNumber, String phone) {}

    @PostMapping("/{slug}/lookup")
    public ResponseEntity<Map<String, Object>> lookup(@PathVariable String slug,
                                                      @RequestBody(required = false) LookupRequest req) {
        String orderNumber = req == null ? null : req.orderNumber();
        String phone       = req == null ? null : req.phone();
        return portal.lookup(slug, orderNumber, phone)
            .map(r -> switch (r.outcome()) {
                case SUCCESS   -> ResponseEntity.ok(r.body());
                case THROTTLED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                      .body(Map.<String, Object>of("message", THROTTLED_MESSAGE));
                case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                                      .body(Map.<String, Object>of("message", NOT_FOUND_MESSAGE));
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
