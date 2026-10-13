package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * POST /api/v1/pieces/{id}/label/reprint — Lookup "Reprint label" (owner / manager).
 * See PieceLabelReprintService.
 */
@RestController
@RequestMapping("/api/v1/pieces")
public class PieceLabelController {

    private final PieceLabelReprintService svc;

    public PieceLabelController(PieceLabelReprintService svc) {
        this.svc = svc;
    }

    // No produces= on the mapping: it would also bind the 422 / 404 JSON error bodies to
    // application/pdf. The content type is set on the success response instead.
    @PostMapping("/{id}/label/reprint")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<byte[]> reprint(
            @PathVariable String id,
            @AuthenticationPrincipal CustomUserDetails principal) throws IOException {
        byte[] pdf = svc.reprint(id, principal.userId());
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + id + ".pdf\"")
            .body(pdf);
    }
}
