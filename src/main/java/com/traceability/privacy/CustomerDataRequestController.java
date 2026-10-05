package com.traceability.privacy;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Settings → Privacy: Shopify customer data requests (GDPR build A). OWNER only — managers and workers
 * get 403. Tenant-scoped under RLS: another tenant's request id answers 404. An expired request answers 410.
 */
@RestController
@RequestMapping("/api/v1/privacy/data-requests")
public class CustomerDataRequestController {

    private final CustomerDataRequestService service;

    public CustomerDataRequestController(CustomerDataRequestService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public List<Map<String, Object>> list(@AuthenticationPrincipal CustomUserDetails principal) {
        return service.list(principal.tenantId());
    }

    @GetMapping("/{id}/export")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<byte[]> export(@PathVariable UUID id,
                                         @AuthenticationPrincipal CustomUserDetails principal) {
        CustomerDataRequestService.Export export = service.export(principal.tenantId(), id, principal.userId());
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(export.filename()).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(export.json());
    }
}
