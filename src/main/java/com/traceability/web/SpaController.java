package com.traceability.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA catch-all: forward unmatched GET requests to index.html so React
 * Router handles client-side routing on direct navigation / page refresh.
 *
 * Pattern [^\\.]*  matches any path segment that contains no dot.
 * Static assets always have a dot in their name (index-abc.js, etc.),
 * so they are naturally excluded. Real API controllers match first via
 * RequestMappingHandlerMapping's higher specificity, so this catch-all
 * is only reached for unmatched SPA routes (/login, /privacy, /orders/…).
 *
 * Two patterns cover single-segment and multi-segment SPA routes:
 *   /{path:[^\\.]*}      /privacy, /login
 *   /{path:[^\\.]*}/**   /orders/123, /settings/profile
 */
@Controller
public class SpaController {

    @GetMapping({"/{path:[^\\.]*}", "/{path:[^\\.]*}/**"})
    public String spa() {
        return "forward:/index.html";
    }
}
