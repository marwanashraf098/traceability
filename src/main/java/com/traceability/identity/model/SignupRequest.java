package com.traceability.identity.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code attribution} is optional, untyped JSON on purpose: a malformed value must never turn
 * a signup into a 400 — {@link SignupAttribution#from} keeps only well-formed string fields.
 */
public record SignupRequest(String tenantName, String name, String email, String phone, String password,
                            boolean consent, JsonNode attribution) {

    public SignupRequest(String tenantName, String name, String email, String phone, String password,
                         boolean consent) {
        this(tenantName, name, email, phone, password, consent, null);
    }
}
