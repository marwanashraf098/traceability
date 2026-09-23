package com.traceability.portal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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

    public PortalController(PortalService portal) {
        this.portal = portal;
    }

    @GetMapping("/{slug}/config")
    public ResponseEntity<Map<String, Object>> config(@PathVariable String slug) {
        return portal.config(slug)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
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
