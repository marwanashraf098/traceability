package com.traceability.identity;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.identity.model.ForgotPasswordRequest;
import com.traceability.identity.model.ForgotPasswordResponse;
import com.traceability.identity.model.LoginRequest;
import com.traceability.identity.model.PinRequest;
import com.traceability.identity.model.ResetPasswordRequest;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String COOKIE_NAME    = "traced_refresh";
    static final String COOKIE_PATH    = "/api/v1/auth/refresh";
    static final int    COOKIE_MAX_AGE = 2_592_000; // 30 days

    // Same generic body regardless of whether the email matched a user — enumeration-safe.
    private static final ForgotPasswordResponse FORGOT_PASSWORD_RESPONSE =
            new ForgotPasswordResponse("If that email is registered, a reset code has been sent.");

    private final AuthService authService;
    private final PinService  pinService;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService, PinService pinService,
                          PasswordResetService passwordResetService) {
        this.authService          = authService;
        this.pinService           = pinService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AccessTokenResponse signup(@RequestBody SignupRequest req, HttpServletResponse response) {
        TokenResponse tokens = authService.signup(req);
        setRefreshCookie(response, tokens.refreshToken(), COOKIE_MAX_AGE);
        return new AccessTokenResponse(tokens.accessToken());
    }

    @PostMapping("/login")
    public AccessTokenResponse login(@RequestBody LoginRequest req, HttpServletResponse response) {
        TokenResponse tokens = authService.login(req);
        setRefreshCookie(response, tokens.refreshToken(), COOKIE_MAX_AGE);
        return new AccessTokenResponse(tokens.accessToken());
    }

    /**
     * Reads the refresh token from the httpOnly cookie, rotates it (revoke old, issue new),
     * writes the new cookie, and returns the new access token in the body.
     * Missing cookie → 401 directly (required=false avoids MissingRequestCookieException path).
     */
    @PostMapping("/refresh")
    public ResponseEntity<AccessTokenResponse> refresh(
            @CookieValue(value = COOKIE_NAME, required = false) String rawToken,
            HttpServletResponse response) {
        if (rawToken == null || rawToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        TokenResponse tokens = authService.refresh(rawToken.trim());
        setRefreshCookie(response, tokens.refreshToken(), COOKIE_MAX_AGE);
        return ResponseEntity.ok(new AccessTokenResponse(tokens.accessToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void logout(@AuthenticationPrincipal CustomUserDetails principal,
                       HttpServletResponse response) {
        authService.logout(principal.userId());
        setRefreshCookie(response, "", 0); // Max-Age=0 expires the cookie immediately
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
                                         HttpServletResponse response) {
        TokenResponse tokens = pinService.switchPin(principal.tenantId(), req, rawRefreshToken);
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
