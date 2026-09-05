package com.traceability.web;

import com.traceability.integrations.shopify.ShopifyOAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SPA catch-all: forward unmatched GET requests to index.html so React
 * Router handles client-side routing on direct navigation / page refresh.
 *
 * PathPatternParser (Spring 5.3+) forbids ** anywhere except at the end of
 * a pattern. That rules out the intuitive "/**-then-no-dot" approach. Instead,
 * each level of depth gets its own pattern, and every variable carries the
 * [^.&amp;]* constraint (no dot = no file extension).
 *
 * Three levels cover all expected SPA routes:
 *   /login  /privacy  /orders              (1 segment)
 *   /orders/123       /settings/profile    (2 segments)
 *   /orders/123/edit                       (3 segments)
 *
 * Static assets always have a dotted extension (.js .css .svg), so none of
 * the variables matches them. They fall through to ResourceHttpRequestHandler
 * which serves them with correct MIME types.
 *
 * Real API controllers registered in RequestMappingHandlerMapping have higher
 * specificity and match first; this catch-all is only reached for unmatched routes.
 *
 * /embedded is served by a separate exact-match method that forwards to embedded.html
 * (the Shopify App Bridge entry point). Exact-path mappings beat the pattern catch-all,
 * so this intercepts /embedded before spa() can forward it to index.html.
 * /embedded.html (with extension) is served directly by ResourceHttpRequestHandler.
 */
@Controller
public class SpaController {

    private static final Logger log = LoggerFactory.getLogger(SpaController.class);
    private static final String SEG = "[^.]*";

    // Same format ShopifyOAuthController.SHOP_DOMAIN_PATTERN validates against — checked
    // here too before `shop` is embedded into the top-level-breakout HTML/JS below, since
    // that context (inline <script>) has different injection risks than a redirect URL.
    private static final String SHOP_DOMAIN_PATTERN = "[a-zA-Z0-9][a-zA-Z0-9-]*\\.myshopify\\.com";

    private final ShopifyOAuthService oauthService;

    public SpaController(ShopifyOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    // Root route — three distinct cases, distinguished by which Shopify params are present.
    // Accepts both GET and POST: Shopify sends a POST (form submission) when initiating
    // install/reinstall from the Partner Dashboard or admin, with shop/hmac/timestamp in
    // the request body.  GET is used for embedded iframe opens.
    //
    // 1. host= present  → EMBEDDED OPEN.
    //    GET: forward to embedded.html (URL stays at / — App Bridge CDN reads host from
    //    window.location.search and establishes the parent-frame handshake).
    //    POST: redirect to GET /? with the same params (Post/Redirect/Get) so App Bridge
    //    can read host from window.location.search (POST body is invisible to JS).
    //
    // 2. shop= present, host= absent  → two sub-cases (Fix 2.3.3, managed framed-open path):
    //
    //    2a. FRAMED BOOTSTRAP — embedded=1 and/or hmac present. This is Shopify's legacy
    //        embedded-app bootstrap hit: it loads application_url INSIDE the admin iframe
    //        with {shop, hmac, embedded} but no host yet. A server-side redirect here stays
    //        trapped inside that iframe and dead-ends at Shopify's own (un-framable) OAuth
    //        consent page → Shopify admin shows its own 404. Fix: respond 200 with an HTML
    //        page whose script does `window.top.location = <admin app URL>` — a top-level
    //        breakout, exempt from framing rules since it isn't itself being framed. Shopify
    //        then re-frames the app at that URL WITH host present, and branch 1 above takes
    //        over normally. adminAppUrl reuses ShopifyOAuthService.buildAdminAppUrl()'s
    //        shop-derived fallback (host is null here) — same URL/logic as the OAuth-callback
    //        fix, not duplicated.
    //
    //    2b. GENUINE FRESH-INSTALL INTENT — shop present, neither embedded nor hmac. A direct
    //        /?shop=... link (not a Shopify-driven admin embed attempt) — unchanged from
    //        before: redirect to /auth/shopify/install to start OAuth.
    //
    // 3. Neither → STANDALONE LANDING PAGE.  Direct browser hit, marketing pages, etc.
    @RequestMapping(value = "/", method = {RequestMethod.GET, RequestMethod.POST})
    public String root(
            @RequestParam(name = "host",  required = false) String host,
            @RequestParam(name = "shop",  required = false) String shop,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        if (host != null) {
            if ("POST".equalsIgnoreCase(request.getMethod())) {
                String dest = "/?" + paramsToQueryString(request.getParameterMap());
                log.debug("[SPA-ROOT] method=POST host={} shop={} → PRG redirect to GET {}", host, shop, dest);
                return "redirect:" + dest;
            }
            log.debug("[SPA-ROOT] method=GET host={} shop={} → forward:/embedded.html", host, shop);
            return "forward:/embedded.html";
        }

        if (shop != null) {
            // embedded=1 ONLY — NOT hmac. hmac is a general Shopify request-signing mechanism
            // present on genuine install/reinstall hits too (SpaRoutingTest's own
            // rootWithShopParamOnlyRedirectsToInstall / rootPostWithShopOnlyRedirectsToInstall /
            // rootPostInstallFormBodyMyshopifyOriginRedirectsToInstall all use shop+hmac+timestamp
            // with NO embedded and must keep redirecting to /auth/shopify/install). embedded=1 is
            // Shopify's specific "this is an admin-embedded app open" marker and is never present
            // on those install-intent hits — it's the reliable signal, not hmac.
            boolean framedBootstrap = request.getParameter("embedded") != null;

            if (framedBootstrap && shop.matches(SHOP_DOMAIN_PATTERN)) {
                String adminAppUrl = oauthService.buildAdminAppUrl(shop, null);
                log.debug("[SPA-ROOT] framed bootstrap (embedded/hmac present, host=null) shop={} → top-level breakout to {}",
                        shop, adminAppUrl);
                writeTopLevelBreakout(response, adminAppUrl);
                return null;
            }

            String qs = paramsToQueryString(request.getParameterMap());
            String dest = "/auth/shopify/install" + (qs.isEmpty() ? "" : "?" + qs);
            log.debug("[SPA-ROOT] method={} host=null shop={} → redirect:{}", request.getMethod(), shop, dest);
            return "redirect:" + dest;
        }

        log.debug("[SPA-ROOT] method={} host=null shop=null → forward:/index.html (landing)", request.getMethod());
        return "forward:/index.html";
    }

    // Writes the response directly (returning null from root() above) rather than going
    // through a view name — the body is a dynamic per-request URL, not a static template.
    // adminAppUrl is safe to inline as-is: it's built from a shop that already passed
    // SHOP_DOMAIN_PATTERN (letters/digits/hyphens/dots only) plus our own trusted
    // shopify.app-handle config — no character requiring HTML/JS escaping can appear in it.
    private void writeTopLevelBreakout(HttpServletResponse response, String adminAppUrl) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(
            "<!doctype html>\n" +
            "<html><head><meta charset=\"UTF-8\"><title>Redirecting…</title></head>\n" +
            "<body>\n" +
            "<script>window.top.location.href = \"" + adminAppUrl + "\";</script>\n" +
            "<noscript><a href=\"" + adminAppUrl + "\">Continue to Traced</a></noscript>\n" +
            "</body></html>"
        );
    }

    // Exact match beats the pattern catch-all — /embedded → embedded.html (App Bridge shell).
    @GetMapping("/embedded")
    public String embedded() {
        return "forward:/embedded.html";
    }

    @GetMapping({
        "/{a:" + SEG + "}",
        "/{a:" + SEG + "}/{b:" + SEG + "}",
        "/{a:" + SEG + "}/{b:" + SEG + "}/{c:" + SEG + "}"
    })
    public String spa() {
        return "forward:/index.html";
    }

    // Rebuilds a query string from the full parameter map (URL params + POST form body).
    // All Shopify OAuth params (shop, hmac, timestamp, host, state) are alphanumeric or
    // contain only URL-safe chars; URLEncoder is a no-op for them but is correct by default.
    private static String paramsToQueryString(Map<String, String[]> params) {
        return params.entrySet().stream()
            .flatMap(e -> Arrays.stream(e.getValue())
                .map(v -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)))
            .collect(Collectors.joining("&"));
    }
}
