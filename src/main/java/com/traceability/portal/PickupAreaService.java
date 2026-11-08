package com.traceability.portal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
        // P4b: a portal pre-connect order (V163) has no forward leg — the city / district are the
        // original Bosta delivery's, stored in portal_delivery (same dropOffAddress shape).
        List<Map<String, Object>> portal = portalDropOff(tenantId, orderId);
        if (!portal.isEmpty()) {
            return forCity((String) portal.get(0).get("city_id"), (String) portal.get(0).get("district_id"));
        }
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

    /**
     * 2026-11-08 — the original address texts the area guess reads (DistrictGuesser): the order's Shopify shipping
     * address (address1, address2, city, province) and the original Bosta delivery's dropOffAddress (firstLine,
     * secondLine, zone name) — portal_delivery for a pre-connect order, else the newest delivered forward leg.
     * PII: never leaves the server (only the guessed district id does), never logged.
     */
    public List<String> originalAddressTexts(UUID tenantId, UUID orderId) {
        return originalAddressFields(tenantId, orderId).stream().map(DistrictGuesser.Field::text).toList();
    }

    /** The same texts, each marked whether it is the Bosta delivery's own zone / secondLine (preferred when ambiguous). */
    public List<DistrictGuesser.Field> originalAddressFields(UUID tenantId, UUID orderId) {
        List<DistrictGuesser.Field> out = new ArrayList<>();
        // A portal pre-connect order (not in merchant_orders): its row through portalDropOff (classified MUST_INCLUDE).
        List<Map<String, Object>> rows = portalDropOff(tenantId, orderId);
        if (rows.isEmpty()) {
            rows = jdbc.queryForList(
                "SELECT o.shopify_address->>'address1' AS a1, o.shopify_address->>'address2' AS a2, " +
                "       o.shopify_address->>'city' AS c, o.shopify_address->>'province' AS p, " +
                "       (SELECT (s.raw->'dropOffAddress')::text FROM shipments s WHERE s.tenant_id = o.tenant_id " +
                "          AND s.order_id = o.id AND s.shipment_leg = 'forward' AND s.delivered_at IS NOT NULL " +
                "        ORDER BY s.created_at DESC, s.id DESC LIMIT 1) AS drop " +
                "FROM merchant_orders o WHERE o.tenant_id = ? AND o.id = ? AND o.pii_redacted_at IS NULL",
                tenantId, orderId);
        }
        for (Map<String, Object> r : rows) {
            for (String k : new String[]{"a1", "a2", "c", "p"}) add(out, (String) r.get(k), false);
            String drop = (String) r.get("drop");
            if (drop != null) {
                try {
                    com.fasterxml.jackson.databind.JsonNode d = new com.fasterxml.jackson.databind.ObjectMapper().readTree(drop);
                    add(out, d.path("firstLine").asText(null), false);
                    add(out, d.path("secondLine").asText(null), true);
                    add(out, d.path("zone").path("name").asText(null), true);
                } catch (Exception ignored) { /* no Bosta address */ }
            }
        }
        return out;
    }

    private static void add(List<DistrictGuesser.Field> out, String v, boolean bosta) {
        if (v != null && !v.isBlank()) out.add(new DistrictGuesser.Field(v, bosta));
    }

    /** The area guess for an order whose original delivery had no district (null outcome district = nothing). */
    public DistrictGuesser.Guess guessArea(UUID tenantId, UUID orderId, CityAreas offer) {
        // The offered districts, with the zone id and drop-off flag the same-zone rule needs (bosta_districts is global
        // reference data).
        java.util.Set<String> offered = new java.util.HashSet<>();
        for (District d : offer.districts()) offered.add(d.id());
        List<DistrictGuesser.Candidate> cs = jdbc.query(
            "SELECT district_id, district_name, district_name_ar, zone_name, zone_name_ar, zone_id, dropoff_available " +
            "FROM bosta_districts WHERE city_id = ? AND pickup_available ORDER BY district_id",
            (rs, i) -> new DistrictGuesser.Candidate(rs.getString("district_id"), rs.getString("district_name"),
                rs.getString("district_name_ar"), rs.getString("zone_name"), rs.getString("zone_name_ar"),
                rs.getString("zone_id"), rs.getBoolean("dropoff_available")),
            offer.cityId()).stream().filter(c -> offered.contains(c.districtId())).toList();
        List<String> cityNames = new ArrayList<>();
        jdbc.query("SELECT DISTINCT city_name, city_name_ar FROM bosta_districts",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                if (rs.getString(1) != null) cityNames.add(rs.getString(1));
                if (rs.getString(2) != null) cityNames.add(rs.getString(2));
            });
        return DistrictGuesser.guess(originalAddressFields(tenantId, orderId), cs, cityNames);
    }

    /**
     * Returns portal fees F2 — the order's delivery city and district ids AS STORED (portal_delivery, else the newest
     * delivered forward leg's raw), for the fee's area when the customer chose no pickup district. Never calls Bosta
     * (no raw refresh); {city, district} with either null when unknown.
     */
    public String[] storedDeliveryArea(UUID tenantId, UUID orderId) {
        List<Map<String, Object>> rows = portalDropOff(tenantId, orderId);
        if (rows.isEmpty()) rows = forwardLeg(tenantId, orderId);
        if (rows.isEmpty()) return new String[]{null, null};
        return new String[]{(String) rows.get(0).get("city_id"), (String) rows.get(0).get("district_id")};
    }

    private List<Map<String, Object>> portalDropOff(UUID tenantId, UUID orderId) {
        return jdbc.queryForList(
            "SELECT o.portal_delivery->'dropOffAddress'->'city'->>'_id' AS city_id, " +
            "       COALESCE(o.portal_delivery->'dropOffAddress'->'district'->>'_id', " +
            "                o.portal_delivery->'dropOffAddress'->>'districtId') AS district_id, " +
            // 2026-11-08 area guess (originalAddressTexts): the same row's address texts — never leave the server.
            "       CASE WHEN o.pii_redacted_at IS NULL THEN o.shopify_address->>'address1' END AS a1, " +
            "       CASE WHEN o.pii_redacted_at IS NULL THEN o.shopify_address->>'address2' END AS a2, " +
            "       CASE WHEN o.pii_redacted_at IS NULL THEN o.shopify_address->>'city' END AS c, " +
            "       CASE WHEN o.pii_redacted_at IS NULL THEN o.shopify_address->>'province' END AS p, " +
            "       CASE WHEN o.pii_redacted_at IS NULL THEN (o.portal_delivery->'dropOffAddress')::text END AS drop " +
            "FROM orders o WHERE o.tenant_id = ? AND o.id = ? AND o.origin = 'portal_pre_connect'",
            tenantId, orderId);
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
