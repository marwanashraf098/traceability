package com.traceability.inventory;

/**
 * Thrown when the packer scans a valid AWB that does not match the AWB already
 * ingested for this order's active forward shipment. Maps to 409 AWB_MISMATCH.
 *
 * Distinct from the swapped-label 409: that error fires when the scanned AWB
 * belongs to a *different* order. This one fires when the order already has its
 * own AWB but the packer scanned a different number (e.g. picked up the wrong label).
 *
 * No INSERT is performed on this path — the caller must throw before reaching any
 * INSERT code.
 */
public class AwbMismatchException extends RuntimeException {

    private final String scannedAwb;
    private final String existingAwb;

    public AwbMismatchException(String scannedAwb, String existingAwb) {
        super("Scanned AWB " + scannedAwb + " does not match this order's AWB " + existingAwb);
        this.scannedAwb = scannedAwb;
        this.existingAwb = existingAwb;
    }

    public String getScannedAwb()  { return scannedAwb; }
    public String getExistingAwb() { return existingAwb; }
}
