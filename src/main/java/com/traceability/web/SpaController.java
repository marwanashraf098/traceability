package com.traceability.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

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

    private static final String SEG = "[^.]*";

    // Root route — three distinct cases, distinguished by which Shopify params are present:
    //
    // 1. host= present  → EMBEDDED OPEN.
    //    Shopify admin iframe loads https://app.tracedtech.com?shop=X&host=Y&embedded=1.
    //    Forward (not redirect) to embedded.html so the browser URL stays at / — CDN App
    //    Bridge fires replaceState('/') which keeps the admin URL at ...apps/{handle} (no
    //    extra path segment that would cause Shopify to 404).
    //
    // 2. shop= present, host= absent  → INSTALL / OAUTH INITIATION.
    //    Shopify sends merchant to application_url with ?shop=X&hmac=...×tamp=... but NO
    //    host= (the app is not yet in an iframe — OAuth hasn't completed).  Forwarding to
    //    embedded.html here is wrong: App Bridge would load in a top-level browser context,
    //    fail to communicate with any parent frame, and OAuth would never run.  The merchant
    //    would then open the app in admin and get Shopify's own 404 (app not installed).
    //    Redirect to /auth/shopify/install preserving the full query string so the HMAC and
    //    shop params reach the install handler.
    //
    // 3. Neither → STANDALONE LANDING PAGE.  Direct browser hit, marketing pages, etc.
    @GetMapping("/")
    public String root(
            @RequestParam(name = "host",  required = false) String host,
            @RequestParam(name = "shop",  required = false) String shop,
            HttpServletRequest request) {
        if (host != null) {
            return "forward:/embedded.html";
        }
        if (shop != null) {
            String qs = request.getQueryString();
            return "redirect:/auth/shopify/install" + (qs != null ? "?" + qs : "");
        }
        return "forward:/index.html";
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
}
