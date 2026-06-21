package com.traceability.integrations.bosta;

/**
 * Bosta returned a date-validation error on createPickup:
 * 1080 = Friday, 1081 = same-day cut-off passed, 1083 = past date, 2022 = holiday.
 */
public class BostaPickupDateException extends BostaException {
    private final int bostaCode;

    public BostaPickupDateException(int bostaCode, String message) {
        super("Bosta pickup date error (" + bostaCode + "): " + message);
        this.bostaCode = bostaCode;
    }

    public int getBostaCode() { return bostaCode; }
}
