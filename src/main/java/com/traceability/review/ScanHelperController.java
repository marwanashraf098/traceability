package com.traceability.review;

import com.traceability.inventory.ScanHelperService;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Review mode S7 — GET /api/v1/scan-helpers/{context}: the candidates a click-to-scan chip offers.
 * Gated server-side: 404 unless the caller's tenant has the scanHelpers capability (the public demo
 * or a simulated-courier review tenant — ReviewCapabilities), so a real merchant can't reach it even
 * by calling the API. Any role (a station-mode PIN worker included).
 *
 *   pieces?variantId=  available pieces of the variant   (pick screens)
 *   waybills           queue orders' waybills            (waybill-scan mode, no order open)
 *   pickup             packed waybills on no open session (pickup session)
 *   returns            delivered pieces awaited on open return requests (return session)
 *   lookup             a few pieces worth tracing        (Lookup)
 */
@RestController
@RequestMapping("/api/v1/scan-helpers")
public class ScanHelperController {

    private final JdbcTemplate      jdbc;
    private final ScanHelperService helpers;

    public ScanHelperController(JdbcTemplate jdbc, ScanHelperService helpers) {
        this.jdbc    = jdbc;
        this.helpers = helpers;
    }

    @GetMapping("/{context}")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public Map<String, Object> candidates(@PathVariable String context,
                                          @RequestParam(required = false) UUID variantId) {
        UUID tenant = TenantContext.require();
        if (!ReviewCapabilities.scanHelpers(jdbc, tenant)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        List<Map<String, Object>> items = switch (context) {
            case "pieces" -> {
                if (variantId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                yield helpers.pieces(tenant, variantId);
            }
            case "waybills" -> helpers.waybills(tenant);
            case "pickup"   -> helpers.pickup(tenant);
            case "returns"  -> helpers.returns(tenant);
            case "lookup"   -> helpers.lookup(tenant);
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
        return Map.of("items", items);
    }
}
