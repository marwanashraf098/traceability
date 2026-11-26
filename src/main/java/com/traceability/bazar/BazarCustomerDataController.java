package com.traceability.bazar;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Bazar customer details on request — {@link BazarCustomerDataService}. Every call is a POST with a JSON body so a
 * phone or a name never sits in a URL (access logs). Search / export: owner or manager; delete preview / delete: owner
 * only. 404 BAZAR_NOT_ENABLED unless {@code tenants.bazar_enabled}.
 */
@RestController
@RequestMapping("/api/v1/bazar-customers")
public class BazarCustomerDataController {

    private final BazarCustomerDataService data;

    public BazarCustomerDataController(BazarCustomerDataService data) {
        this.data = data;
    }

    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody SearchRequest req, @AuthenticationPrincipal CustomUserDetails p) {
        return data.search(req.q(), actor(p));
    }

    /** Audit-logged (bazar_customer_export, counts only). */
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody KeysRequest req, @AuthenticationPrincipal CustomUserDetails p) {
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bazar-customer-details.csv\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(data.exportCsv(req.keys(), actor(p)).getBytes(StandardCharsets.UTF_8));
    }

    @PreAuthorize("hasRole('OWNER')")
    @PostMapping("/delete-preview")
    public Map<String, Object> deletePreview(@RequestBody KeysRequest req, @AuthenticationPrincipal CustomUserDetails p) {
        return data.deletePreview(req.keys(), actor(p));
    }

    /** Cannot be undone. Audit-logged (bazar_customer_delete, counts only). */
    @PreAuthorize("hasRole('OWNER')")
    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestBody DeleteRequest req, @AuthenticationPrincipal CustomUserDetails p) {
        return data.delete(req.keys(), req.expectedRows(), actor(p));
    }

    private static BazarSaleService.Actor actor(CustomUserDetails p) {
        return new BazarSaleService.Actor(p.userId(), p.role());
    }

    public record SearchRequest(String q) {}

    public record KeysRequest(List<String> keys) {}

    public record DeleteRequest(List<String> keys,
                                @JsonProperty("expected_rows") @JsonAlias("expectedRows") Integer expectedRows) {}
}
