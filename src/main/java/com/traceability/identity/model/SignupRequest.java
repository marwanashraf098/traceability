package com.traceability.identity.model;

public record SignupRequest(String tenantName, String name, String email, String password, boolean consent) {}
