package com.traceability.identity.model;

/** Always the same shape/status regardless of whether the email matched a user — enumeration-safe. */
public record ForgotPasswordResponse(String message) {}
