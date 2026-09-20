package com.traceability.demo;

/**
 * Mirrors {@link com.traceability.identity.model.AccessTokenResponse} plus a redirect hint.
 * The access token travels in the body — no cookie is set, no refresh token is minted
 * (FR-DEMO Day 2 §3). Day 3's frontend calls the existing setAccessToken(data.accessToken)
 * then pushes to redirect client-side; it never relies on a server-side 302 or cookie.
 */
public record DemoStartResponse(String accessToken, String redirect) {}
