package com.traceability.web;

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

    // Root route: forward to the App Bridge shell when Shopify loads the embedded app.
    // Shopify sets application_url = https://app.tracedtech.com (root) and adds ?host=&shop=.
    //
    // MUST be a forward (not a redirect). The CDN App Bridge script fires replaceState on init,
    // which sends navigate('app:{pathname}') to the parent admin frame. A redirect would move
    // the browser to /embedded, App Bridge would then push 'app:/embedded' to the admin, and
    // the admin URL would become ...apps/{handle}/embedded — a path Shopify admin does not
    // recognise → Shopify's own 404. With a forward the URL stays at / (just query params),
    // App Bridge pushes 'app:/' → admin URL stays at ...apps/{handle} (no extra segment).
    //
    // nginx's `location = /` block carries frame-ancestors CSP and strips X-Frame-Options: DENY
    // so the admin iframe loads correctly (see nginx.conf).
    @GetMapping("/")
    public String root(
            @RequestParam(name = "host",  required = false) String host,
            @RequestParam(name = "shop",  required = false) String shop) {
        if (host != null || shop != null) {
            return "forward:/embedded.html";
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
