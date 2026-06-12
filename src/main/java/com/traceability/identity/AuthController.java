package com.traceability.identity;

import com.traceability.identity.model.LoginRequest;
import com.traceability.identity.model.PinRequest;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final PinService  pinService;

    public AuthController(AuthService authService, PinService pinService) {
        this.authService = authService;
        this.pinService  = pinService;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse signup(@RequestBody SignupRequest req) {
        return authService.signup(req);
    }

    @PostMapping("/login")
    public TokenResponse login(@RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@RequestBody String rawToken) {
        return authService.refresh(rawToken.trim());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void logout(@AuthenticationPrincipal CustomUserDetails principal) {
        authService.logout(principal.userId());
    }

    /** Switches the attributed worker via PIN within the current tenant session. */
    @PostMapping("/pin")
    @PreAuthorize("isAuthenticated()")
    public TokenResponse pinSwitch(@RequestBody PinRequest req,
                                   @AuthenticationPrincipal CustomUserDetails principal) {
        return pinService.switchPin(principal.tenantId(), req);
    }
}
