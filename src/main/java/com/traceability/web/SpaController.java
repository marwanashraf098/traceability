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

    // Root route: redirect to the App Bridge shell when Shopify loads the embedded app.
    // Shopify sets application_url = https://app.tracedtech.com (root) and adds ?host=&shop=.
    // We redirect (not forward) so the final URL is /embedded?... — the nginx location block
    // for ^~ /embedded carries frame-ancestors CSP and strips X-Frame-Options, which is
    // required for the iframe to load. A forward from / would inherit the server-level DENY.
    // Preserves all query params so App Bridge's CDN script can read ?host= from the URL.
    @GetMapping("/")
    public String root(
            @RequestParam(name = "host",  required = false) String host,
            @RequestParam(name = "shop",  required = false) String shop,
            HttpServletRequest request) {
        if (host != null || shop != null) {
            String qs = request.getQueryString();
            return "redirect:/embedded" + (qs != null ? "?" + qs : "");
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
