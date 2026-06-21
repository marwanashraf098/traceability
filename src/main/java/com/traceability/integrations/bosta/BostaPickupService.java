package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

/**
 * Bosta pickup scheduling + internal manifest generation.
 *
 * Two modes (pickup_mode on courier_accounts):
 *
 *   BOSTA_MANAGED (default): merchant's daily pickup is managed by Bosta directly.
 *     Traced NEVER calls createPickup. We only generate the internal manifest
 *     (list of awaiting_pickup shipments, COD per parcel, total COD).
 *
 *   TRACED_MANAGED: Traced calls POST /api/v2/pickups to schedule the day's pickup.
 *     Date validation runs first (Friday / past date rejected client-side;
 *     cut-off / holiday let the Bosta API respond). "Already exists" codes
 *     1078/2024–2027 are surfaced as a non-error message, not a crash.
 *
 * Manifest is always generated for BOTH modes — it is Traced's internal document
 * (merchant records, hand to courier); Bosta is not involved in it.
 */
@Service
public class BostaPickupService {

    private static final Logger log = LoggerFactory.getLogger(BostaPickupService.class);

    private final JdbcTemplate       jdbc;
    private final TransactionTemplate tx;
    private final BostaGateway        bostaGateway;
    private final EncryptionService   encryptionService;
    private final ObjectMapper        mapper;

    public BostaPickupService(JdbcTemplate jdbc,
                               PlatformTransactionManager txm,
                               BostaGateway bostaGateway,
                               EncryptionService encryptionService,
                               ObjectMapper mapper) {
        this.jdbc             = jdbc;
        this.tx               = new TransactionTemplate(txm);
        this.bostaGateway     = bostaGateway;
        this.encryptionService = encryptionService;
        this.mapper           = mapper;
    }

    // ── Public types ──────────────────────────────────────────────────────────

    public record ManifestLine(String trackingNumber, String orderNumber, BigDecimal cod) {}

    public record PickupManifest(
        UUID     pickupId,
        String   scheduledDate,
        String   mode,
        String   providerPickupId,
        String   alreadyExistsMessage,
        List<ManifestLine> shipments,
        BigDecimal totalCod,
        int      parcelCount
    ) {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Schedule a pickup for the given date and generate the internal manifest.
     *
     * Pre-conditions checked client-side:
     *   - Past dates: rejected with 400
     *   - Friday (Bosta error 1080): rejected with 400
     *   (Same-day cut-off 1081 and holiday 2022 are let through to the Bosta API.)
     */
    public PickupManifest schedulePickup(UUID tenantId, LocalDate scheduledDate) {

        // 1. Date validation (pre-API checks)
        LocalDate today = LocalDate.now();
        if (scheduledDate.isBefore(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Scheduled date is in the past");
        }
        if (scheduledDate.getDayOfWeek() == DayOfWeek.FRIDAY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Bosta does not schedule pickups on Fridays (error 1080)");
        }

        // 2. Load courier account (API key + settings)
        record AccountRow(String apiKeyEncrypted, String pickupMode,
                          String locationId, JsonNode contactPerson) {}

        AccountRow account = TenantContext.runAs(tenantId, () ->
            tx.execute(s -> jdbc.query(
                "SELECT api_key_encrypted, pickup_mode, pickup_business_location_id, contact_person " +
                "FROM courier_accounts " +
                "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
                rs -> {
                    if (!rs.next()) return null;
                    JsonNode cp = null;
                    String cpStr = rs.getString("contact_person");
                    if (cpStr != null) {
                        try { cp = mapper.readTree(cpStr); } catch (Exception ignored) {}
                    }
                    return new AccountRow(
                        rs.getString("api_key_encrypted"),
                        rs.getString("pickup_mode"),
                        rs.getString("pickup_business_location_id"),
                        cp);
                },
                tenantId)));

        if (account == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active Bosta account");
        }

        // 3. Load awaiting_pickup shipments not already in an active pickup.
        //    "Awaiting pickup" = order.status = 'awaiting_pickup' (order_status enum).
        //    Shipments remain in 'created' internal state until Bosta state 21 fires.
        List<Map<String, Object>> shipmentRows = TenantContext.runAs(tenantId, () ->
            tx.execute(s -> jdbc.queryForList(
                "SELECT s.id, s.tracking_number, s.cod_amount, " +
                "       o.number AS order_number " +
                "FROM shipments s " +
                "JOIN orders o ON o.id = s.order_id " +
                "WHERE s.tenant_id = ? " +
                "  AND o.status = 'awaiting_pickup'::order_status " +
                "  AND NOT EXISTS ( " +
                "      SELECT 1 FROM pickup_shipments ps " +
                "      JOIN pickups p ON p.id = ps.pickup_id " +
                "      WHERE ps.shipment_id = s.id " +
                "        AND ps.tenant_id   = ? " +
                "        AND p.status NOT IN ('cancelled') " +
                "  )",
                tenantId, tenantId)));

        // 4. Create pickup record + link shipments (before calling Bosta API, so manifest
        //    exists regardless of TRACED_MANAGED API result)
        String dateStr    = scheduledDate.toString();
        int parcelCount   = shipmentRows.size();
        BigDecimal total  = shipmentRows.stream()
            .map(r -> r.get("cod_amount") != null ? (BigDecimal) r.get("cod_amount") : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Load courier_account_id for FK
        UUID accountId = TenantContext.runAs(tenantId, () ->
            tx.execute(s -> jdbc.query(
                "SELECT id FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                tenantId)));

        UUID pickupId = TenantContext.runAs(tenantId, () ->
            tx.execute(s -> {
                UUID pid = jdbc.query(
                    "INSERT INTO pickups (tenant_id, courier_account_id, scheduled_date, status, total_cod_amount) " +
                    "VALUES (?, ?, ?, 'pending', ?) RETURNING id",
                    rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    tenantId, accountId, java.sql.Date.valueOf(scheduledDate), total);

                if (pid == null) throw new RuntimeException("pickup INSERT returned no id");

                // Link shipments
                for (Map<String, Object> row : shipmentRows) {
                    UUID sid = (UUID) row.get("id");
                    jdbc.update(
                        "INSERT INTO pickup_shipments (pickup_id, shipment_id, tenant_id) VALUES (?, ?, ?)",
                        pid, sid, tenantId);
                }
                return pid;
            }));

        // 5. TRACED_MANAGED: call Bosta createPickup
        String providerPickupId    = null;
        String alreadyExistsMsg    = null;
        String mode                = account.pickupMode() != null ? account.pickupMode() : "BOSTA_MANAGED";

        if ("TRACED_MANAGED".equals(mode)) {
            if (account.locationId() == null || account.locationId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bosta pickup businessLocationId not configured in settings");
            }

            String rawApiKey = encryptionService.decrypt(account.apiKeyEncrypted());
            try {
                providerPickupId = bostaGateway.createPickup(
                    rawApiKey, dateStr, account.locationId(), account.contactPerson(), parcelCount);

                // Persist provider ID on the pickup row
                final String pid = providerPickupId;
                TenantContext.runAs(tenantId, () ->
                    tx.execute(s -> {
                        jdbc.update("UPDATE pickups SET provider_pickup_id = ? WHERE id = ?",
                            pid, pickupId);
                        return null;
                    }));

                log.info("TRACED_MANAGED pickup created: tenantId={} bostaPickupId={} date={}",
                    tenantId, providerPickupId, dateStr);

            } catch (BostaPickupAlreadyExistsException e) {
                // A pickup already exists for today — surface as informational message.
                // Common when merchant also has Bosta auto-pickup enabled. Not a crash.
                alreadyExistsMsg = "A Bosta pickup already exists for " + dateStr +
                    " (code " + e.getBostaCode() + "). Your manifest is ready.";
                log.info("TRACED_MANAGED pickup: already exists for {} (code {})",
                    dateStr, e.getBostaCode());

            } catch (BostaPickupDateException e) {
                // Date rejected by Bosta — delete the pickup record we just created and propagate
                final UUID pid = pickupId;
                TenantContext.runAs(tenantId, () ->
                    tx.execute(s -> {
                        jdbc.update("DELETE FROM pickup_shipments WHERE pickup_id = ?", pid);
                        jdbc.update("DELETE FROM pickups WHERE id = ?", pid);
                        return null;
                    }));
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bosta rejected pickup date: " + e.getMessage());
            }
        } else {
            log.info("BOSTA_MANAGED: skipping createPickup for tenantId={} date={}", tenantId, dateStr);
        }

        // 6. Build manifest
        List<ManifestLine> lines = shipmentRows.stream()
            .map(r -> new ManifestLine(
                (String) r.get("tracking_number"),
                (String) r.get("order_number"),
                r.get("cod_amount") != null ? (BigDecimal) r.get("cod_amount") : BigDecimal.ZERO))
            .toList();

        return new PickupManifest(
            pickupId, dateStr, mode, providerPickupId, alreadyExistsMsg,
            lines, total, parcelCount);
    }

    /** Re-fetch a manifest for an already-created pickup (for GET endpoint). */
    public PickupManifest getManifest(UUID tenantId, UUID pickupId) {
        Map<String, Object> pickup = TenantContext.runAs(tenantId, () ->
            tx.execute(s -> jdbc.query(
                "SELECT id, scheduled_date::text, status, provider_pickup_id, total_cod_amount " +
                "FROM pickups WHERE id = ? AND tenant_id = ?",
                rs -> rs.next() ? Map.<String, Object>of(
                    "id",                 rs.getObject("id", UUID.class),
                    "scheduled_date",     rs.getString("scheduled_date"),
                    "status",             rs.getString("status"),
                    "provider_pickup_id", rs.getString("provider_pickup_id"),
                    "total_cod_amount",   rs.getBigDecimal("total_cod_amount")
                ) : null,
                pickupId, tenantId)));

        if (pickup == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pickup not found");
        }

        List<ManifestLine> lines = TenantContext.runAs(tenantId, () ->
            tx.execute(s -> jdbc.query(
                "SELECT s.tracking_number, s.cod_amount, o.number AS order_number " +
                "FROM pickup_shipments ps " +
                "JOIN shipments s ON s.id = ps.shipment_id " +
                "JOIN orders    o ON o.id = s.order_id " +
                "WHERE ps.pickup_id = ? AND ps.tenant_id = ?",
                (rs, i) -> new ManifestLine(
                    rs.getString("tracking_number"),
                    rs.getString("order_number"),
                    rs.getBigDecimal("cod_amount") != null
                        ? rs.getBigDecimal("cod_amount") : BigDecimal.ZERO),
                pickupId, tenantId)));

        BigDecimal total = (BigDecimal) pickup.get("total_cod_amount");
        if (total == null) {
            total = lines.stream().map(ManifestLine::cod).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        return new PickupManifest(
            (UUID) pickup.get("id"),
            (String) pickup.get("scheduled_date"),
            null,  // mode not stored on pickup row (comes from courier_accounts)
            (String) pickup.get("provider_pickup_id"),
            null,
            lines,
            total,
            lines.size());
    }
}
