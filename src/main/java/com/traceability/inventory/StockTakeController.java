package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-21: Stock Taking.
 *
 * POST /api/v1/stock-takes/sessions — open a session + snapshot the full piece
 * population in scope (Step 2).
 */
@RestController
@RequestMapping("/api/v1/stock-takes")
public class StockTakeController {

    private final StockTakeService stockTake;

    public StockTakeController(StockTakeService stockTake) {
        this.stockTake = stockTake;
    }

    @PostMapping("/sessions")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<Map<String, Object>> openSession(
            @RequestBody OpenSessionRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {
        List<UUID> variantIds = req.variantIds() == null ? null :
            req.variantIds().stream().map(UUID::fromString).toList();
        UUID locationId = req.locationId() != null ? UUID.fromString(req.locationId()) : null;

        Map<String, Object> result = stockTake.openSession(
            req.scopeType(), variantIds, locationId, req.note(), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    public record OpenSessionRequest(
        String scopeType, List<String> variantIds, String locationId, String note) {}
}
