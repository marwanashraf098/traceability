package com.traceability.identity;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.identity.model.LoginRequest;
import com.traceability.identity.model.PinRequest;
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

    private final AuthService authService;
    private final PinService  pinService;

    public AuthController(AuthService authService, PinService pinService) {
        this.authService = authService;
        this.pinService  = pinService;
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
     * PIN switch issues a new access token attributed to the worker.
     * No refresh cookie is written — the device session continues under the original cookie.
     */
    @PostMapping("/pin")
    @PreAuthorize("isAuthenticated()")
    public AccessTokenResponse pinSwitch(@RequestBody PinRequest req,
                                         @AuthenticationPrincipal CustomUserDetails principal) {
        TokenResponse tokens = pinService.switchPin(principal.tenantId(), req);
        return new AccessTokenResponse(tokens.accessToken());
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
