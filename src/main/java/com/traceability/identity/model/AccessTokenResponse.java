package com.traceability.identity.model;

/** Response body for login, signup, refresh, and pin-switch endpoints.
 *  The refresh token is delivered via httpOnly cookie — never in this body. */
public record AccessTokenResponse(String accessToken) {}
