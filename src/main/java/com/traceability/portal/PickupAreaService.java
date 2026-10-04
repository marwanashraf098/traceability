package com.traceability.portal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Returns portal Step 4c-2 — the pickup area a Bosta courier collects a return from.
 *
 * The city is the one the order was delivered to: the newest delivered forward leg's
 * {@code raw.dropOffAddress.city._id} (the same leg the return window is measured on). The
 * choices are that city's pickup-available districts from the global bosta_districts table.
 * Only city/zone/district NAMES and ids ever leave this class — never the street address.
 *
 * Callers run inside TenantContext + a transaction (bosta_districts itself is global
 * reference data with no RLS; the shipments read is tenant-scoped as usual).
 */
@Service
public class PickupAreaService {

    public record District(String id, String name, String nameAr, String zoneName, String zoneNameAr) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("nameAr", nameAr);
            m.put("zoneName", zoneName);
            m.put("zoneNameAr", zoneNameAr);
            return m;
        }
    }

    /** A city with at least one pickup-available district. */
    public record CityAreas(String cityId, String cityName, String cityNameAr, List<District> districts,
                            String forwardDistrictId) {

        public Optional<District> find(String districtId) {
            if (districtId == null) return Optional.empty();
            return districts.stream().filter(d -> d.id().equals(districtId)).findFirst();
        }

        /** The forward leg's district when it is one of the choices, else null. */
        public String preselectedDistrictId() {
            return find(forwardDistrictId).map(District::id).orElse(null);
        }

        Map<String, Object> toJson(boolean withPreselected) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cityId", cityId);
            m.put("cityName", cityName);
            m.put("cityNameAr", cityNameAr);
            m.put("districts", districts.stream().map(District::toJson).toList());
            if (withPreselected) m.put("preselectedDistrictId", preselectedDistrictId());
            return m;
        }
    }

    /** A Bosta city (id and names only). */
    public record City(String id, String name, String nameAr) {
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("nameAr", nameAr);
            return m;
        }
    }

    private final JdbcTemplate jdbc;

    /**
     * Portal custom address (V117) — every Bosta city with at least one pickup-available
     * district, by name. Reference data only (bosta_districts is global).
     */
    public List<City> pickupCities() {
        return jdbc.query(
            "SELECT city_id, MIN(city_name) AS city_name, MIN(city_name_ar) AS city_name_ar " +
            "FROM bosta_districts WHERE pickup_available GROUP BY city_id " +
            "ORDER BY MIN(city_name) NULLS LAST, city_id",
            (rs, i) -> new City(rs.getString("city_id"), rs.getString("city_name"), rs.getString("city_name_ar")));
    }

    public PickupAreaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Lazy v0 (2026-10-04): set on the Spring-built services; hand-built instances (tests) have none.
    private com.traceability.integrations.bosta.ShipmentRawRefresher rawRefresher;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRawRefresher(com.traceability.integrations.bosta.ShipmentRawRefresher rawRefresher) {
        this.rawRefresher = rawRefresher;
    }

    /** The order's delivery city and its pickup-available districts; empty when unknown or none. */
    public Optional<CityAreas> forOrder(UUID tenantId, UUID orderId) {
        List<Map<String, Object>> leg = forwardLeg(tenantId, orderId);
        if (leg.isEmpty()) return Optional.empty();
        // A v2 search copy without the city (2026-10-04): fetch Bosta's v0 delivery once, then read again.
        if (leg.get(0).get("city_id") == null && rawRefresher != null
                && com.traceability.integrations.bosta.BostaListItemCache.SHAPE_V2.equals(leg.get(0).get("raw_shape"))
                && rawRefresher.refreshV0(tenantId, (UUID) leg.get(0).get("id"))) {
            leg = forwardLeg(tenantId, orderId);
        }
        return forCity((String) leg.get(0).get("city_id"), (String) leg.get(0).get("district_id"));
    }

    private List<Map<String, Object>> forwardLeg(UUID tenantId, UUID orderId) {
        return jdbc.queryForList(
            "SELECT s.id, s.raw->>'_tracedRawShape' AS raw_shape, s.raw->'dropOffAddress'->'city'->>'_id' AS city_id, " +
            "       COALESCE(s.raw->'dropOffAddress'->'district'->>'_id', " +
            "                s.raw->'dropOffAddress'->>'districtId') AS district_id " +
            "FROM shipments s " +
            "WHERE s.tenant_id = ? AND s.order_id = ? AND s.shipment_leg = 'forward' " +
            "  AND s.delivered_at IS NOT NULL " +
            "ORDER BY s.created_at DESC, s.id DESC LIMIT 1",
            tenantId, orderId);
    }

    /**
     * Step 5c — the districts of {@code cityId} Bosta can both deliver to and collect from (an
     * exchange courier does both in one visit): pickup_available AND dropoff_available.
     */
    public List<String> exchangeDistrictIds(String cityId) {
        if (cityId == null || cityId.isBlank()) return List.of();
        return jdbc.queryForList(
            "SELECT district_id FROM bosta_districts WHERE city_id = ? AND pickup_available AND dropoff_available " +
            "ORDER BY zone_name NULLS LAST, district_name, district_id", String.class, cityId);
    }

    /** A city's pickup-available districts (zone, then district name); empty when none. */
    public Optional<CityAreas> forCity(String cityId, String forwardDistrictId) {
        return forCity(cityId, forwardDistrictId, false);
    }

    /**
     * A city's pickup-available districts — and, when {@code exchange}, only those Bosta can also
     * deliver to (pickup_available AND dropoff_available, as {@link #exchangeDistrictIds}).
     */
    public Optional<CityAreas> forCity(String cityId, String forwardDistrictId, boolean exchange) {
        if (cityId == null || cityId.isBlank()) return Optional.empty();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT district_id, district_name, district_name_ar, zone_name, zone_name_ar, city_name, city_name_ar " +
            "FROM bosta_districts WHERE city_id = ? AND pickup_available " +
            (exchange ? "AND dropoff_available " : "") +
            "ORDER BY zone_name NULLS LAST, district_name, district_id",
            cityId);
        if (rows.isEmpty()) return Optional.empty();
        List<District> districts = rows.stream().map(r -> new District(
            (String) r.get("district_id"), (String) r.get("district_name"), (String) r.get("district_name_ar"),
            (String) r.get("zone_name"), (String) r.get("zone_name_ar"))).toList();
        return Optional.of(new CityAreas(cityId, (String) rows.get(0).get("city_name"),
            (String) rows.get(0).get("city_name_ar"), districts, forwardDistrictId));
    }
}
