package com.traceability.integrations.bosta;

/**
 * Thrown when Bosta returns HTTP 400 with body "Delivery not found" for a tracking number.
 *
 * Distinct from BostaException (generic API errors) so callers can handle this specific
 * case — e.g. BostaStatusPollJob stamps provider_not_found_at and drops the shipment
 * from the poll set rather than logging an error every cycle (FR-14).
 */
public class DeliveryNotFoundException extends BostaException {

    private final String trackingNumber;

    public DeliveryNotFoundException(String trackingNumber) {
        super("Delivery not found in Bosta: " + trackingNumber);
        this.trackingNumber = trackingNumber;
    }

    public String getTrackingNumber() { return trackingNumber; }
}
