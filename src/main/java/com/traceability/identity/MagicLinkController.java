package com.traceability.identity;

import com.traceability.identity.model.TokenResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Consumes a magic-link token and issues a persistent httpOnly cookie session.
 *
 * GET /auth/magic?token=<raw> (permitAll — authenticated by token hash, not JWT).
 *
 * Flow: hash incoming token → consume_magic_link DEFINER (atomic validate+consume)
 * → set httpOnly traced_refresh cookie → 302 to app root with NO tokens in the URL.
 * On load the SPA calls POST /api/v1/auth/refresh (cookie auto-sent) to get an access token.
 *
 * Previously redirected to /#access_token=...&refresh_token=... — that approach was broken
 * (the SPA never parsed the fragment) and leaked the refresh token into browser history.
 *
 * Rate limiting per NFR-3: TODO — add Bucket4j or similar once infra is confirmed.
 * The DEFINER's FOR UPDATE guard prevents concurrent double-consume.
 */
@RestController
public class MagicLinkController {

    private final MagicLinkService magicLinkService;
    private final String appUrl;

    public MagicLinkController(MagicLinkService magicLinkService,
                                @Value("${shopify.app-url:http://localhost:5173}") String appUrl) {
        this.magicLinkService = magicLinkService;
        this.appUrl           = appUrl;
    }

    @GetMapping("/auth/magic")
    public ResponseEntity<Void> consume(@RequestParam String token,
                                        HttpServletResponse response) {
        TokenResponse tokens = magicLinkService.consumeMagicLink(token);
        // Deliver the refresh token as an httpOnly cookie — never in the URL.
        // The SPA calls POST /api/v1/auth/refresh on load to get the access token.
        AuthController.setRefreshCookie(response, tokens.refreshToken(), AuthController.COOKIE_MAX_AGE);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(appUrl + "/"))
                .build();
    }
}
