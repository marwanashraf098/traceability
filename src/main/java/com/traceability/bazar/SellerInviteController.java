package com.traceability.bazar;

import com.traceability.identity.AuthController;
import com.traceability.identity.MagicLinkService;
import com.traceability.integrations.shopify.ShopifyOAuthException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Bazar B6b — GET /auth/seller-invite?token=… (permitAll; the token IS the credential). Consumed through the EXISTING
 * hatch #6 consume_magic_link (MagicLinkService.consumeMagicLinkFor — no new hatch), the normal 30-day refresh cookie,
 * then 302 to {@code /bazar/join/{eventId}} (pick a PIN if none, then the Sell screen). Expired / used / unknown →
 * 302 to {@code /bazar/invite-expired} ("This link has expired — ask the owner for a new one."), no cookie. A link of a
 * seller removed from the event → their sessions are revoked again and the same expired page.
 */
@RestController
public class SellerInviteController {

    private final MagicLinkService   magicLinks;
    private final BazarSellerService sellers;
    private final String             appUrl;

    public SellerInviteController(MagicLinkService magicLinks, BazarSellerService sellers,
                                  @Value("${shopify.app-url:http://localhost:5173}") String appUrl) {
        this.magicLinks = magicLinks;
        this.sellers    = sellers;
        this.appUrl     = appUrl;
    }

    @GetMapping("/auth/seller-invite")
    public ResponseEntity<Void> open(@RequestParam(required = false) String token, HttpServletResponse response) {
        if (token == null || token.isBlank()) return redirect("/bazar/invite-expired");
        MagicLinkService.Consumed c;
        try {
            c = magicLinks.consumeMagicLinkFor(token);
        } catch (ShopifyOAuthException e) {
            return redirect("/bazar/invite-expired");
        }
        BazarSellerService.Landing landing = sellers.landing(c.tenantId(), c.userId(), c.tokenHash());
        if (landing != null && landing.removed()) return redirect("/bazar/invite-expired");
        AuthController.setRefreshCookie(response, c.tokens().refreshToken(), AuthController.COOKIE_MAX_AGE);
        return redirect(landing == null ? "/" : "/bazar/join/" + landing.eventId());
    }

    private ResponseEntity<Void> redirect(String path) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(appUrl + path)).build();
    }
}
