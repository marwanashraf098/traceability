package com.traceability.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA catch-all: forward unmatched non-API, non-static GET requests to the
 * app shell (index.html) so React Router can handle client-side routing on
 * direct navigation or page refresh.
 *
 * Two path patterns cover single-segment (/privacy) and multi-segment (/orders/123):
 *   /{path:REGEX}     — first segment, no trailing slash
 *   /{path:REGEX}/**  — first segment + anything after
 *
 * The regex negative-lookahead excludes the namespaces that have real server handlers:
 *   api       — REST API controllers
 *   actuator  — management endpoints
 *   webhooks  — Bosta / Shopify webhook receivers
 *   auth      — Shopify OAuth callbacks / magic-link consume
 *   assets    — static JS/CSS bundles (ResourceHttpRequestHandler)
 *
 * Spring MVC gives exact-match and more-specific patterns higher priority, so
 * this catch-all is only reached when no other handler matches the path.
 */
@Controller
public class SpaController {

    private static final String SPA_REGEX =
            "^(?!api|actuator|webhooks|auth|assets).*";

    @GetMapping({
        "/{path:" + SPA_REGEX + "}",
        "/{path:" + SPA_REGEX + "}/**"
    })
    public String spa() {
        return "forward:/index.html";
    }
}
