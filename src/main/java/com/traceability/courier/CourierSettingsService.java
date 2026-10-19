package com.traceability.courier;

import com.traceability.account.AuditService;
import com.traceability.integrations.shopify.FulfillmentTrackingCapture;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Non-Bosta couriers G2 — Settings → Couriers: the "I ship with other couriers" switch
 * ({@code tenants.generic_courier_enabled}) and the default carrier name ({@code generic_default_carrier}, V171).
 *
 * Owner writes (the controller gates it), owner + manager read. Every change of either value is one
 * audit_log row (who, when, from → to). Switching ON enqueues the one-time {@link GenericCatchUpJob} after the
 * transaction commits. Switching OFF writes the flag only: no new generic shipment is created from then on and
 * the existing ones are left exactly as they are — not cancelled (a 'created' one may already be on a parcel the
 * merchant handed to that courier); their orders stay packable (WaybillScanKeys keeps applying while a live
 * generic shipment exists) and their alerts keep showing (ExceptionService's flag-or-legs gate).
 */
@Service
public class CourierSettingsService {

    public record Settings(boolean enabled, String defaultCarrier, List<String> suggestions, boolean bostaConnected) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final JobScheduler jobScheduler;

    public CourierSettingsService(JdbcTemplate jdbc, AuditService audit, JobScheduler jobScheduler) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.jobScheduler = jobScheduler;
    }

    @Transactional(readOnly = true)
    public Settings get() {
        UUID tenantId = TenantContext.require();
        Map<String, Object> t = jdbc.queryForMap(
            "SELECT generic_courier_enabled, generic_default_carrier, " +
            "       EXISTS (SELECT 1 FROM courier_accounts ca WHERE ca.tenant_id = t.id AND ca.provider = 'bosta' " +
            "               AND ca.status = 'active') AS bosta " +
            "FROM tenants t WHERE t.id = ?", tenantId);
        return new Settings(Boolean.TRUE.equals(t.get("generic_courier_enabled")),
            (String) t.get("generic_default_carrier"), suggestions(), Boolean.TRUE.equals(t.get("bosta")));
    }

    /**
     * Carrier names to suggest: other_known_carriers() (the one SQL list), in the spelling merchants use —
     * the most common spelling this tenant's Shopify fulfillments used for it, else a title-cased name.
     */
    List<String> suggestions() {
        List<String> names = jdbc.queryForList("SELECT unnest(other_known_carriers())", String.class);
        Map<String, String> seen = new HashMap<>();
        jdbc.query(
            "SELECT lower(trim(carrier_raw)) AS k, trim(carrier_raw) AS v, count(*) AS n FROM order_fulfillment_tracking " +
            "WHERE tenant_id = ? AND carrier_raw IS NOT NULL AND lower(trim(carrier_raw)) = ANY (other_known_carriers()) " +
            "GROUP BY 1, 2 ORDER BY 3 DESC, 2",
            rs -> { seen.putIfAbsent(rs.getString("k"), rs.getString("v")); }, TenantContext.require());
        List<String> out = new ArrayList<>();
        for (String n : names) {
            String v = seen.getOrDefault(n, titleCase(n));
            if (!out.contains(v)) out.add(v);
        }
        return out;
    }

    @Transactional
    public Settings update(UUID actorUserId, boolean enabled, String defaultCarrier) {
        UUID tenantId = TenantContext.require();
        String carrier = defaultCarrier == null ? null : defaultCarrier.strip();
        if (carrier != null && carrier.isEmpty()) carrier = null;
        if (carrier != null && carrier.length() > 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Carrier name is too long (60 characters at most)");
        }
        Map<String, Object> before = jdbc.queryForMap(
            "SELECT generic_courier_enabled, generic_default_carrier FROM tenants WHERE id = ? FOR UPDATE", tenantId);
        boolean wasEnabled = Boolean.TRUE.equals(before.get("generic_courier_enabled"));
        String wasCarrier = (String) before.get("generic_default_carrier");

        jdbc.update("UPDATE tenants SET generic_courier_enabled = ?, generic_default_carrier = ? WHERE id = ?",
            enabled, carrier, tenantId);

        if (wasEnabled != enabled) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", wasEnabled);
            m.put("to", enabled);
            audit.record(actorUserId, "generic_courier_switch", "tenant", tenantId.toString(), m);
        }
        if (!Objects.equals(wasCarrier, carrier)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", wasCarrier);
            m.put("to", carrier);
            audit.record(actorUserId, "generic_default_carrier", "tenant", tenantId.toString(), m);
        }
        if (!wasEnabled && enabled) enqueueCatchUpAfterCommit(tenantId);
        return get();
    }

    /** The catch-up's dry run for this tenant: what switching on (or the job) would create now. */
    @Transactional(readOnly = true)
    public GenericCatchUp.DryRun catchUpPreview() {
        return GenericCatchUp.dryRun(jdbc, TenantContext.require(), FulfillmentTrackingCapture::carrierClass);
    }

    private void enqueueCatchUpAfterCommit(UUID tenantId) {
        Runnable enqueue = () -> jobScheduler.<GenericCatchUpJob>enqueue(job -> job.run(tenantId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { enqueue.run(); }
            });
        } else {
            enqueue.run();
        }
    }

    private static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        boolean up = true;
        for (char c : s.toCharArray()) {
            b.append(up ? Character.toUpperCase(c) : c);
            up = c == ' ';
        }
        String out = b.toString();
        return out.replace("J&t", "J&T").replace("Smsa", "SMSA").replace("Qp ", "QP ").replace("R2s", "R2S");
    }
}
