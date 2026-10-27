package com.traceability.setup;

/**
 * The main warehouse's address as saved in Traced (locations.address_*, V186). {@code provinceCode}
 * is the Shopify / ISO-3166-2 code (C, GZ, ALX …); building and floor may be null. Not customer PII —
 * it is the merchant's own warehouse — but it is still never logged.
 */
public record WarehouseAddress(String provinceCode, String city, String street, String building, String floor) {

    @Override
    public String toString() {
        return "WarehouseAddress[" + provinceCode + "]";
    }
}
