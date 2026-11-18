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
 * Receiving move M1 — "Moving from my Shopify location" (ReceivingMoveService). Owner / manager, like Receiving. Read
 * only towards Shopify: no endpoint here writes to Shopify.
 */
@RestController
@RequestMapping("/api/v1/receiving")
public class ReceivingMoveController {

    private final ReceivingMoveService moves;

    public ReceivingMoveController(ReceivingMoveService moves) {
        this.moves = moves;
    }

    /** The merchant's own active Shopify locations to move stock from (all pages; Traced's and app locations out). */
    @GetMapping("/move-sources")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<ReceivingMoveService.Source> sources() {
        return moves.listSources();
    }

    /** Creates a move session — behind the move gate (a fresh, verified Ready of Traced Main's shipping). */
    @PostMapping("/move-sessions")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateMoveSessionRequest req,
                                                      @AuthenticationPrincipal CustomUserDetails principal) {
        UUID id = moves.createSession(principal.userId(),
            req.locationId() != null ? UUID.fromString(req.locationId()) : null,
            req.reference(), req.supplierName(), req.note(), req.mode(),
            req.labelRunId() != null ? UUID.fromString(req.labelRunId()) : null,
            req.sourceLocationId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("sessionId", id.toString()));
    }

    @GetMapping("/sessions/{sessionId}/move/levels")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> levels(@PathVariable UUID sessionId) {
        return moves.levels(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/move/preview")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> preview(@PathVariable UUID sessionId) {
        return moves.preview(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/move/progress")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> progress(@PathVariable UUID sessionId) {
        return moves.progress(sessionId);
    }

    /** One line's leftover rule ('keep' | 'zero'), applied to every line of its variant in the session. */
    @PutMapping("/sessions/{sessionId}/lines/{lineId}/source-choice")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setLineChoice(@PathVariable UUID sessionId, @PathVariable UUID lineId, @RequestBody ChoiceRequest req) {
        moves.setLineChoice(sessionId, lineId, req.choice());
    }

    /** "Set all to 0" ('zero') or "Keep them there" for every line ('keep'). */
    @PostMapping("/sessions/{sessionId}/move/source-choice")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> setAllChoice(@PathVariable UUID sessionId, @RequestBody ChoiceRequest req) {
        return Map.of("linesUpdated", moves.setAllChoice(sessionId, req.choice()));
    }

    public record CreateMoveSessionRequest(String locationId, String reference, String supplierName, String note,
                                           String mode, String labelRunId, String sourceLocationId) {}

    public record ChoiceRequest(String choice) {}
}
