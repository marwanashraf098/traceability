package com.traceability.identity;

import com.traceability.identity.model.TokenResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Consumes a magic-link token and issues a standalone JWT session.
 *
 * GET /auth/magic?token=<raw> (permitAll — authenticated by token hash, not JWT).
 *
 * Flow: hash incoming token → consume_magic_link DEFINER (atomic validate+consume)
 * → issue access + refresh → 302 to app with tokens in URL fragment.
 *
 * Tokens are delivered in the URL fragment (#) so they are NOT sent to the server
 * on subsequent requests. The SPA reads them from window.location.hash and stores
 * them in memory / localStorage per its existing auth flow.
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
    public ResponseEntity<Void> consume(@RequestParam String token) {
        TokenResponse tokens = magicLinkService.consumeMagicLink(token);
        // Deliver tokens via URL fragment — never sent to server in subsequent requests.
        String destination = appUrl + "/#access_token=" + tokens.accessToken()
            + "&refresh_token=" + tokens.refreshToken();
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(destination))
            .build();
    }
}
