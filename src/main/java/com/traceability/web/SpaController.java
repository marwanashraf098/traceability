package com.traceability.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    // 2. shop= present, host= absent  → INSTALL / OAUTH INITIATION.
    //    GET or POST: redirect to /auth/shopify/install with all params in the query
    //    string.  getParameterMap() covers both URL params and POST form body.
    //
    // 3. Neither → STANDALONE LANDING PAGE.  Direct browser hit, marketing pages, etc.
    @RequestMapping(value = "/", method = {RequestMethod.GET, RequestMethod.POST})
    public String root(
            @RequestParam(name = "host",  required = false) String host,
            @RequestParam(name = "shop",  required = false) String shop,
            HttpServletRequest request) {

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
            String qs = paramsToQueryString(request.getParameterMap());
            String dest = "/auth/shopify/install" + (qs.isEmpty() ? "" : "?" + qs);
            log.debug("[SPA-ROOT] method={} host=null shop={} → redirect:{}", request.getMethod(), shop, dest);
            return "redirect:" + dest;
        }

        log.debug("[SPA-ROOT] method={} host=null shop=null → forward:/index.html (landing)", request.getMethod());
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
