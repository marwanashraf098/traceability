package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.CustomUserDetails;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shopify")
public class ShopifyController {

    private final ShopifySyncService syncService;
    private final ShopifyImportJob   importJob;
    private final JobScheduler       jobScheduler;
    private final JdbcTemplate       jdbc;
    private final ObjectMapper       mapper;
    private final TransactionTemplate tx;

    public ShopifyController(ShopifySyncService syncService,
                              ShopifyImportJob importJob,
                              JobScheduler jobScheduler,
                              JdbcTemplate jdbc,
                              ObjectMapper mapper,
                              PlatformTransactionManager txm) {
        this.syncService   = syncService;
        this.importJob     = importJob;
        this.jobScheduler  = jobScheduler;
        this.jdbc          = jdbc;
        this.mapper        = mapper;
        this.tx            = new TransactionTemplate(txm);
    }

    public record ConnectRequest(String shopDomain, String adminToken) {}
    public record ConnectResponse(String storeId, String importStatus) {}
    public record StoreStatusResponse(String storeId, String importStatus, Object importSummary) {}

    /** Validates credentials + enqueues background import. Returns 202 immediately. */
    @PostMapping("/connect")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ConnectResponse> connect(
            @RequestBody ConnectRequest req,
            @AuthenticationPrincipal CustomUserDetails principal) {

        ShopifySyncService.ConnectResult result =
            syncService.connect(principal.tenantId(), req.shopDomain(), req.adminToken());

        UUID storeId  = result.storeId();
        UUID tenantId = principal.tenantId();
        jobScheduler.enqueue(() -> importJob.run(storeId, tenantId));

        return ResponseEntity.accepted()
            .body(new ConnectResponse(storeId.toString(), "pending"));
    }

    /** Lists all connected stores for the current tenant. */
    @GetMapping("/stores")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Object listStores(@AuthenticationPrincipal CustomUserDetails principal) {
        return tx.execute(s -> jdbc.queryForList(
            "SELECT id, shop_domain, status, import_status, last_sync_at FROM stores WHERE tenant_id = ?",
            principal.tenantId()));
    }

    /** Re-runs the Shopify import synchronously for an already-connected store. */
    @PostMapping("/stores/{storeId}/sync")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> sync(
            @PathVariable UUID storeId,
            @AuthenticationPrincipal CustomUserDetails principal) {
        importJob.run(storeId, principal.tenantId());
        return ResponseEntity.ok().build();
    }

    /** Returns the current import status for a store (owner or manager). */
    @GetMapping("/stores/{storeId}/status")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public StoreStatusResponse storeStatus(
            @PathVariable UUID storeId,
            @AuthenticationPrincipal CustomUserDetails principal) {

        return tx.execute(s -> jdbc.query(
            "SELECT id, import_status, import_summary FROM stores WHERE id = ?",
            rs -> {
                if (!rs.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found");
                String summaryJson = rs.getString("import_summary");
                Object summary = null;
                if (summaryJson != null) {
                    try { summary = mapper.readValue(summaryJson, Object.class); }
                    catch (Exception ignored) { summary = summaryJson; }
                }
                return new StoreStatusResponse(
                    rs.getObject("id", UUID.class).toString(),
                    rs.getString("import_status"),
                    summary);
            }, storeId));
    }
}
