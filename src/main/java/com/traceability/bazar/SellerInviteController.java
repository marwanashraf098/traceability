package com.traceability.bazar;

import com.traceability.identity.AuthController;
import com.traceability.identity.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bazar B10 — opening a seller invite (SellerInviteService has the rules). All three are permitAll: the token is the
 * credential.
 * <ul>
 *   <li>GET {@code /auth/seller-invite?t=…} — reads and writes NOTHING (a link preview stops here): 302 to the landing
 *       page {@code /bazar/invite?t=…}; the token rides in the link's fragment, which the browser keeps across the
 *       redirect and never sends. A link from before B10 ({@code ?token=…}) is sent on with the token moved into the
 *       fragment.</li>
 *   <li>POST {@code /api/v1/auth/seller-invite/check} {t, token} — the landing page's read.</li>
 *   <li>POST {@code /api/v1/auth/seller-invite/redeem} {t, token} — "Start selling": consumes the link, sets the
 *       30-day refresh cookie, answers {access_token, event_id}; the app then opens {@code /bazar/join/{event}}.</li>
 * </ul>
 */
@RestController
public class SellerInviteController {

    private final SellerInviteService invites;
    private final String              appUrl;

    public SellerInviteController(SellerInviteService invites,
                                  @Value("${shopify.app-url:http://localhost:5173}") String appUrl) {
        this.invites = invites;
        this.appUrl  = appUrl;
    }

    public record InviteRequest(String t, String token) {}

    @GetMapping("/auth/seller-invite")
    public ResponseEntity<Void> open(@RequestParam(required = false) String t,
                                     @RequestParam(required = false) String token) {
        UriComponentsBuilder to = UriComponentsBuilder.fromUriString(appUrl + "/bazar/invite");
        if (t != null && !t.isBlank()) to.queryParam("t", t.trim());
        if (token != null && !token.isBlank()) to.fragment("token=" + token.trim());
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(to.encode().build().toUriString())).build();
    }

    @PostMapping(value = "/api/v1/auth/seller-invite/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> check(@RequestBody InviteRequest req, HttpServletRequest request) {
        return invites.check(req.t(), req.token(), caller(request));
    }

    @PostMapping(value = "/api/v1/auth/seller-invite/redeem", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> redeem(@RequestBody InviteRequest req, HttpServletRequest request,
                                      HttpServletResponse response) {
        SellerInviteService.Redeemed r = invites.redeem(req.t(), req.token(), caller(request));
        AuthController.setRefreshCookie(response, r.refreshToken(), AuthController.COOKIE_MAX_AGE);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", r.accessToken());
        out.put("event_id", r.eventId().toString());
        return out;
    }

    private static SellerInviteService.Caller caller(HttpServletRequest request) {
        UUID userId = null, tenantId = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails p) {
            userId   = p.userId();
            tenantId = p.tenantId();
        }
        return new SellerInviteService.Caller(request.getHeader(HttpHeaders.ORIGIN),
            request.getHeader(HttpHeaders.REFERER), request.getRemoteAddr(),
            request.getHeader(HttpHeaders.AUTHORIZATION), userId, tenantId);
    }
}
