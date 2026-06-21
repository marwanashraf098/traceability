package com.traceability.integrations.bosta;

/** Bosta returned error code 1078 or 2024–2027: a pickup already exists for this date. */
public class BostaPickupAlreadyExistsException extends BostaException {
    private final int bostaCode;

    public BostaPickupAlreadyExistsException(int bostaCode, String message) {
        super("Bosta pickup already exists (" + bostaCode + "): " + message);
        this.bostaCode = bostaCode;
    }

    public int getBostaCode() { return bostaCode; }
}
