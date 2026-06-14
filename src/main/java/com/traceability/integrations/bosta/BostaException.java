package com.traceability.integrations.bosta;

public class BostaException extends RuntimeException {
    public BostaException(String message) { super(message); }
    public BostaException(String message, Throwable cause) { super(message, cause); }
}
