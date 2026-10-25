package com.traceability.setup;

import com.traceability.account.AuditService;
import com.traceability.geo.EgyptProvinces;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/**
 * Onboarding gate step 2 (and the grandfathered tenants' "Add your warehouse address" banner) — the
 * main warehouse address. ONE writer of locations.address_*.
 *
 * Save: the address goes onto the tenant's main warehouse row (is_fulfillment) first; when Shopify
 * was already connected (the sticky tenants.shopify_connected_at) the gate is done
 * (tenants.setup_completed_at). A tenant with no main warehouse gets one created by the save. The
 * address reaches Shopify after commit through {@link MainLocationAddressPushJob} (feature A's fill rule)
 * — a failure there never undoes the save or blocks the merchant.
 */
@Service
public class SetupAddressService {

    static final String MAIN_WAREHOUSE_NAME = "Main Warehouse";

    static final int CITY_MAX = 100, STREET_MIN = 3, STREET_MAX = 200, PART_MAX = 50;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final JobScheduler jobScheduler;

    public SetupAddressService(JdbcTemplate jdbc, AuditService audit, JobScheduler jobScheduler) {
        this.jdbc         = jdbc;
        this.audit        = audit;
        this.jobScheduler = jobScheduler;
    }

    public record SaveRequest(String provinceCode, String city, String street, String building, String floor) {}

    /** The saved address (null when none) + the governorate picker with each one's Bosta areas. */
    @Transactional(readOnly = true)
    public Map<String, Object> get() {
        UUID tenantId = TenantContext.require();
        Map<String, Object> address = jdbc.query(
            "SELECT address_province_code, address_city, address_street, address_building, address_floor " +
            "FROM locations WHERE tenant_id = ? AND is_fulfillment AND address_province_code IS NOT NULL",
            rs -> {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("provinceCode", rs.getString(1));
                m.put("city",         rs.getString(2));
                m.put("street",       rs.getString(3));
                m.put("building",     rs.getString(4));
                m.put("floor",        rs.getString(5));
                return m;
            },
            tenantId);

        // Bosta zones per city (global reference data, bosta_districts) → the picker's "City / area"
        // suggestions. Empty when the table hasn't been refreshed — the field then takes free text.
        Map<String, List<Map<String, String>>> areasByCity = new HashMap<>();
        jdbc.query(
            "SELECT lower(city_name), zone_name, max(zone_name_ar) FROM bosta_districts " +
            "WHERE zone_name IS NOT NULL AND btrim(zone_name) <> '' " +
            "GROUP BY lower(city_name), zone_name ORDER BY zone_name",
            rs -> {
                Map<String, String> a = new LinkedHashMap<>();
                a.put("nameEn", rs.getString(2));
                a.put("nameAr", rs.getString(3));
                areasByCity.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(a);
            });

        List<Map<String, Object>> provinces = new ArrayList<>();
        for (EgyptProvinces.Governorate g : EgyptProvinces.PICKER) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("code",   g.code());
            p.put("nameEn", g.nameEn());
            p.put("nameAr", g.nameAr());
            p.put("areas",  areasByCity.getOrDefault(g.bostaCity().toLowerCase(Locale.ROOT), List.of()));
            provinces.add(p);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("address",   address);
        out.put("provinces", provinces);
        return out;
    }

    /** Saves the address; returns the tenant's setupStep after the save (null = gate done). */
    @Transactional
    public Map<String, Object> save(UUID actorUserId, SaveRequest req) {
        UUID tenantId = TenantContext.require();
        WarehouseAddress a = validate(req);

        UUID locationId = mainWarehouseCreatingIfMissing(tenantId);
        jdbc.update(
            "UPDATE locations SET address_province_code = ?, address_city = ?, address_street = ?, " +
            "       address_building = ?, address_floor = ? " +
            "WHERE id = ? AND tenant_id = ?",
            a.provinceCode(), a.city(), a.street(), a.building(), a.floor(), locationId, tenantId);
        jdbc.update(
            "UPDATE tenants SET setup_completed_at = now() " +
            "WHERE id = ? AND setup_completed_at IS NULL AND shopify_connected_at IS NOT NULL",
            tenantId);
        // Governorate only — the street address itself is never copied into the audit log.
        audit.record(actorUserId, "warehouse_address_set", "location", locationId.toString(),
            Map.of("provinceCode", a.provinceCode()));

        enqueuePushAfterCommit(tenantId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("setupStep", SetupStatus.step(jdbc, tenantId));
        return out;
    }

    /**
     * The tenant's main warehouse (is_fulfillment), locked for the save. A tenant without one gets it
     * created here (approved 2026-10-25 — no 409): same row as signup's seed (AuthRepository) and the
     * Shopify provisioning's (ensureInternalLocation), so the next Shopify import links it to Shopify;
     * until then the push job's outcome is not_linked and it retries. A same-named row that isn't
     * flagged is promoted, like provisioning does — the tenant ends with exactly one.
     */
    private UUID mainWarehouseCreatingIfMissing(UUID tenantId) {
        UUID id = lockMainWarehouse(tenantId);
        if (id != null) return id;
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (gen_random_uuid(), ?, ?, 'warehouse', " +
            "        NOT EXISTS (SELECT 1 FROM locations WHERE tenant_id = ? AND is_default), true) " +
            "ON CONFLICT (tenant_id, lower(trim(name))) DO NOTHING",
            tenantId, MAIN_WAREHOUSE_NAME, tenantId);
        id = lockMainWarehouse(tenantId);
        if (id != null) return id;
        jdbc.update(
            "UPDATE locations SET is_fulfillment = true WHERE tenant_id = ? AND lower(trim(name)) = lower(trim(?))",
            tenantId, MAIN_WAREHOUSE_NAME);
        return lockMainWarehouse(tenantId);
    }

    private UUID lockMainWarehouse(UUID tenantId) {
        return jdbc.query(
            "SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment FOR UPDATE",
            rs -> rs.next() ? (UUID) rs.getObject(1) : null, tenantId);
    }

    private void enqueuePushAfterCommit(UUID tenantId) {
        Runnable enqueue = () -> jobScheduler.<MainLocationAddressPushJob>enqueue(j -> j.push(tenantId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { enqueue.run(); }
            });
        } else {
            enqueue.run();
        }
    }

    static WarehouseAddress validate(SaveRequest req) {
        if (req == null) throw SetupException.invalid("ADDRESS_REQUIRED",
            "Enter the warehouse address.", "أدخل عنوان المخزن.");
        EgyptProvinces.Governorate g = EgyptProvinces.pickable(req.provinceCode()).orElseThrow(() ->
            SetupException.invalid("GOVERNORATE_REQUIRED", "Choose a governorate.", "اختر المحافظة."));
        String city = clean(req.city());
        if (city == null || city.length() > CITY_MAX) throw SetupException.invalid("CITY_REQUIRED",
            "Enter the city or area.", "أدخل المدينة أو المنطقة.");
        String street = clean(req.street());
        if (street == null || street.length() < STREET_MIN || street.length() > STREET_MAX)
            throw SetupException.invalid("STREET_REQUIRED",
                "Enter the street address.", "أدخل عنوان الشارع.");
        String building = clean(req.building());
        String floor    = clean(req.floor());
        if ((building != null && building.length() > PART_MAX) || (floor != null && floor.length() > PART_MAX))
            throw SetupException.invalid("ADDRESS_PART_TOO_LONG",
                "Building and floor can be at most 50 characters.", "المبنى والدور بحد أقصى 50 حرفًا.");
        return new WarehouseAddress(g.code(), city, street, building, floor);
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").strip();
        return t.isEmpty() ? null : t;
    }
}
