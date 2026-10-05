package com.traceability.identity;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.identity.model.ForgotPasswordRequest;
import com.traceability.identity.model.ForgotPasswordResponse;
import com.traceability.identity.model.LoginRequest;
import com.traceability.identity.model.PinRequest;
import com.traceability.identity.model.ResetPasswordRequest;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.inventory.ScanPairingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String COOKIE_NAME    = "traced_refresh";
    static final String COOKIE_PATH    = "/api/v1/auth/refresh";
    static final int    COOKIE_MAX_AGE = 2_592_000; // 30 days

    // Same generic body regardless of whether the email matched a user — enumeration-safe.
    private static final ForgotPasswordResponse FORGOT_PASSWORD_RESPONSE =
            new ForgotPasswordResponse("If that email is registered, a reset code has been sent.");

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final PinService  pinService;
    private final PasswordResetService passwordResetService;
    private final ScanPairingService   scanPairings;
    private final JwtService           jwtService;
    private final AuthRepository       authRepository;

    public AuthController(AuthService authService, PinService pinService,
                          PasswordResetService passwordResetService, ScanPairingService scanPairings,
                          JwtService jwtService, AuthRepository authRepository) {
        this.authService          = authService;
        this.pinService           = pinService;
        this.passwordResetService = passwordResetService;
        this.scanPairings         = scanPairings;
        this.jwtService           = jwtService;
        this.authRepository       = authRepository;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AccessTokenResponse signup(@RequestBody SignupRequest req, HttpServletRequest request,
                                      HttpServletResponse response) {
        TokenResponse tokens = authService.signup(req, request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
        setRefreshCookie(response, tokens.refreshToken(), COOKIE_MAX_AGE);
        return new AccessTokenResponse(tokens.accessToken());
    }

    @PostMapping("/login")
    public AccessTokenResponse login(@RequestBody LoginRequest req, HttpServletRequest request,
                                     HttpServletResponse response) {
        TokenResponse tokens = authService.login(req, request.getHeader(HttpHeaders.USER_AGENT));
        setRefreshCookie(response, tokens.refreshToken(), COOKIE_MAX_AGE);
        return new AccessTokenResponse(tokens.accessToken());
    }

    /**
     * Reads the refresh token from the httpOnly cookie, rotates it (revoke old, issue new),
     * writes the new cookie, and returns the new access token in the body.
     * Missing cookie → 401 directly (required=false avoids MissingRequestCookieException path).
     * The same token presented again within 30 s of its rotation gets the same successor (V142).
     */
    @PostMapping("/refresh")
    public ResponseEntity<AccessTokenResponse> refresh(
            @CookieValue(value = COOKIE_NAME, required = false) String rawToken,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (rawToken == null || rawToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        TokenResponse tokens = authService.refresh(rawToken.trim(), request.getHeader(HttpHeaders.USER_AGENT));
        setRefreshCookie(response, tokens.refreshToken(), COOKIE_MAX_AGE);
        return ResponseEntity.ok(new AccessTokenResponse(tokens.accessToken()));
    }

    /**
     * "Log out" — this device only (V142). Ends this device's refresh token (the access token's
     * sid, plus the cookie when a caller sends it) and this device's phone pairing; the user's
     * other sessions — a station tablet above all — are untouched. {@code deviceId} is the
     * browser's station device id (localStorage); without it the user's pairings end as before.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void logout(@AuthenticationPrincipal CustomUserDetails principal,
                       @CookieValue(value = COOKIE_NAME, required = false) String rawRefreshToken,
                       @RequestParam(value = "deviceId", required = false) String deviceId,
                       HttpServletRequest request,
                       HttpServletResponse response) {
        authService.logoutDevice(principal.userId(),
                jwtService.sessionIdOf(request.getHeader(HttpHeaders.AUTHORIZATION)), rawRefreshToken);
        // Q1: a logout ends the user's paired phone on this device (best-effort, like the
        // PIN-switch hook — the logout itself must never fail on it; the 12 h expiry is the backstop).
        try {
            if (!scanPairings.revokeForUserOnDevice(deviceId, principal.userId(), "signed_out")) {
                scanPairings.revokeForUser(principal.userId(), "signed_out");
            }
        } catch (RuntimeException e) {
            log.warn("Logout: couldn't revoke the user's phone pairings: {}", e.toString());
        }
        setRefreshCookie(response, "", 0); // Max-Age=0 expires the cookie immediately
    }

    /** "Log out of all devices" — every refresh token and phone pairing of the user (today's revoke-all). */
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void logoutAll(@AuthenticationPrincipal CustomUserDetails principal,
                          HttpServletResponse response) {
        authService.logoutAll(principal.userId());
        try {
            scanPairings.revokeForUser(principal.userId(), "signed_out");
        } catch (RuntimeException e) {
            log.warn("Logout of all devices: couldn't revoke the user's phone pairings: {}", e.toString());
        }
        setRefreshCookie(response, "", 0);
    }

    /**
     * PIN switch issues a new access token attributed to the worker AND rotates the
     * device's traced_refresh cookie to the same worker: the incoming cookie's refresh
     * token (if any) is revoked and replaced with one minted for the switched-in user.
     * Without this, a later /auth/refresh (page reload, idle timeout) would silently
     * re-derive identity from the stored refresh row's original owner/manager.
     */
    @PostMapping("/pin")
    @PreAuthorize("isAuthenticated()")
    public AccessTokenResponse pinSwitch(@RequestBody PinRequest req,
                                         @AuthenticationPrincipal CustomUserDetails principal,
                                         @CookieValue(value = COOKIE_NAME, required = false) String rawRefreshToken,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        TokenResponse tokens = pinService.switchPin(principal.tenantId(), req, rawRefreshToken);
        // V142: the browser never sends the refresh cookie here (its path is /auth/refresh), so the
        // token this tablet held until now is found by the access token's sid and ended — without
        // this every PIN switch left a live orphan token behind. Best-effort, like the hook below:
        // the new tokens are already minted.
        try {
            UUID previousSession = jwtService.sessionIdOf(request.getHeader(HttpHeaders.AUTHORIZATION));
            if (previousSession != null) {
                authRepository.revokeRefreshTokenById(previousSession, principal.userId(), "pin_switch");
            }
            authRepository.stampUserAgent(tokens.refreshToken(), request.getHeader(HttpHeaders.USER_AGENT));
        } catch (RuntimeException e) {
            log.warn("PIN switch: couldn't end the tablet's previous refresh token: {}", e.toString());
        }
        // S6 / Q1: the station now belongs to another worker — the outgoing worker's paired phone
        // must stop scanning into this tablet.
        // A failure here mustn't strand the switch (its tokens are already minted): a leftover
        // pairing can't reach the incoming worker — the relay stream is served only to the
        // pairing's own worker (ScanPairingService.requireStreamable) — so its scans expire
        // undelivered.
        if (req.userId() != null && !req.userId().equals(principal.userId().toString())) {
            try {
                scanPairings.revokeForUser(principal.userId(), "worker_switched");
            } catch (RuntimeException e) {
                log.warn("PIN switch: couldn't revoke the outgoing worker's phone pairings: {}", e.toString());
            }
        }
        setRefreshCookie(response, tokens.refreshToken(), COOKIE_MAX_AGE);
        return new AccessTokenResponse(tokens.accessToken());
    }

    /**
     * Always 200 with the same generic body, whether or not the email matched an active,
     * password-having user. requestReset() itself never throws for a not-found/passwordless/
     * throttled case — it silently no-ops — so there is no branch here to leak from.
     */
    @PostMapping("/forgot-password")
    public ForgotPasswordResponse forgotPassword(@RequestBody ForgotPasswordRequest req) {
        passwordResetService.requestReset(req.email());
        return FORGOT_PASSWORD_RESPONSE;
    }

    /** 200 on success; generic 401 (ResponseStatusException from the service) on any failure. */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.OK)
    public void resetPassword(@RequestBody ResetPasswordRequest req) {
        passwordResetService.resetPassword(req.email(), req.code(), req.newPassword());
    }

    /** Sets or clears the httpOnly refresh-token cookie. Package-visible for MagicLinkController. */
    static void setRefreshCookie(HttpServletResponse response, String value, int maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
