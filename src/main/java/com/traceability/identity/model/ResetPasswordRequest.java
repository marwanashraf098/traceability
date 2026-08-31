package com.traceability.identity.model;

public record ResetPasswordRequest(String email, String code, String newPassword) {}
