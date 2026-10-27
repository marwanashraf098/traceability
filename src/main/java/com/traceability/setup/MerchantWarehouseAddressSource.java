package com.traceability.setup;

import com.traceability.integrations.shopify.ShopifyLocationGateway.LocationAddress;
import com.traceability.inventory.MainLocationAddressSource;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * Feature A's {@link MainLocationAddressSource}, fed by the onboarding gate's saved main warehouse address
 * (locations.address_*, V186). Every path that creates or fills the Traced Main Warehouse Shopify location
 * (provisioning, a new fulfillment location, the V184 backfill, {@link MainLocationAddressPushJob}) uses
 * the merchant's address when one is saved, else A's generic one.
 *
 * Mapping (approved 2026-10-25): governorate → provinceCode, city / area → city, street → address1,
 * building + floor → address2, country EG, and NO zip — only the generic address carries 11511.
 * Called with the tenant context set; joins the caller's transaction when one is open.
 */
@Component
@Primary
public class MerchantWarehouseAddressSource implements MainLocationAddressSource {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public MerchantWarehouseAddressSource(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx   = new TransactionTemplate(txm);
        this.tx.setReadOnly(true);
    }

    @Override
    public Optional<LocationAddress> merchantAddress(UUID tenantId) {
        WarehouseAddress a = tx.execute(s -> jdbc.query(
            "SELECT address_province_code, address_city, address_street, address_building, address_floor " +
            "FROM locations WHERE tenant_id = ? AND is_fulfillment AND address_province_code IS NOT NULL",
            rs -> rs.next() ? new WarehouseAddress(rs.getString(1), rs.getString(2), rs.getString(3),
                                                   rs.getString(4), rs.getString(5)) : null,
            tenantId));
        return Optional.ofNullable(a).map(MerchantWarehouseAddressSource::toShopify);
    }

    static LocationAddress toShopify(WarehouseAddress a) {
        String address2 = a.building() == null ? a.floor()
                        : a.floor() == null    ? a.building()
                        : a.building() + ", " + a.floor();
        return new LocationAddress(a.street(), address2, a.city(), a.provinceCode(), null, "EG");
    }
}
