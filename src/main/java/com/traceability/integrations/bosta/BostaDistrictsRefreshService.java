package com.traceability.integrations.bosta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Returns portal Step 4c-2 — refreshes the global {@code bosta_districts} reference table from
 * Bosta's public {@code GET /api/v2/cities/getAllDistricts} (no Authorization header).
 *
 * OWNER CONNECTION, this table only (approved 2026-09-25): app_user holds SELECT only on
 * bosta_districts (V105 revokes INSERT/UPDATE/DELETE), so the writes go through the
 * {@code @FlywayDataSource} pool — the same pool ExceptionDigestJob and JobRunr use. The table
 * has no tenant_id and holds no tenant data; nothing else is read or written here.
 *
 * One transaction: upsert every district returned (stamped with this run's time), then set
 * pickup_available = false on every row this run did not touch. Rows are never deleted.
 * An empty or failed fetch changes nothing (it never deactivates the whole table).
 * Logs counts only. A 401/403 is logged as "needs auth" and nothing else happens — no
 * tenant's key is ever used for this.
 */
@Service
public class BostaDistrictsRefreshService {

    private static final Logger log = LoggerFactory.getLogger(BostaDistrictsRefreshService.class);

    public record Result(int fetched, int upserted, int deactivated, boolean authRequired) {}

    private final BostaV2Client client;
    private final JdbcTemplate ownerJdbc;
    private final TransactionTemplate ownerTx;

    public BostaDistrictsRefreshService(BostaV2Client client, @FlywayDataSource DataSource ownerDs) {
        this.client    = client;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.ownerTx   = new TransactionTemplate(new DataSourceTransactionManager(ownerDs));
    }

    public boolean isEmpty() {
        Boolean any = ownerJdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bosta_districts)", Boolean.class);
        return !Boolean.TRUE.equals(any);
    }

    public Result refresh() {
        List<BostaV2Client.District> districts;
        try {
            districts = client.fetchAllDistricts();
        } catch (BostaV2Client.AuthRequiredException e) {
            log.error("Bosta districts refresh: getAllDistricts now requires authentication ({}). " +
                      "Nothing was changed; no tenant API key is used for this endpoint.", e.getMessage());
            return new Result(0, 0, 0, true);
        }
        if (districts.isEmpty()) {
            log.warn("Bosta districts refresh: Bosta returned 0 districts — nothing changed");
            return new Result(0, 0, 0, false);
        }

        Result result = ownerTx.execute(s -> {
            Timestamp runAt = ownerJdbc.queryForObject("SELECT now()", Timestamp.class);
            List<Object[]> rows = new ArrayList<>(districts.size());
            for (BostaV2Client.District d : districts) {
                rows.add(new Object[]{d.districtId(), d.cityId(), d.cityName(), d.cityNameAr(),
                    d.zoneId(), d.zoneName(), d.zoneNameAr(), d.districtName(), d.districtNameAr(),
                    d.pickupAvailable(), d.dropoffAvailable(), runAt});
            }
            int[] counts = ownerJdbc.batchUpdate(
                "INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, zone_id, zone_name, " +
                "    zone_name_ar, district_name, district_name_ar, pickup_available, dropoff_available, refreshed_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (district_id) DO UPDATE SET " +
                "    city_id = EXCLUDED.city_id, city_name = EXCLUDED.city_name, city_name_ar = EXCLUDED.city_name_ar, " +
                "    zone_id = EXCLUDED.zone_id, zone_name = EXCLUDED.zone_name, zone_name_ar = EXCLUDED.zone_name_ar, " +
                "    district_name = EXCLUDED.district_name, district_name_ar = EXCLUDED.district_name_ar, " +
                "    pickup_available = EXCLUDED.pickup_available, dropoff_available = EXCLUDED.dropoff_available, " +
                "    refreshed_at = EXCLUDED.refreshed_at",
                rows);
            int upserted = 0;
            for (int c : counts) upserted += Math.max(c, 0);
            int deactivated = ownerJdbc.update(
                "UPDATE bosta_districts SET pickup_available = false " +
                "WHERE refreshed_at < ? AND pickup_available", runAt);
            return new Result(districts.size(), upserted, deactivated, false);
        });
        log.info("Bosta districts refresh: fetched={} upserted={} deactivated={}",
            result.fetched(), result.upserted(), result.deactivated());
        return result;
    }
}
