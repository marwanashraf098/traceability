package com.traceability.demo;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-DEMO Day 2 — public, unauthenticated demo entry point. Rides the existing
 * JwtAuthenticationFilter/TenantContextFilter chain like /api/v1/auth/login does: both are
 * no-ops when no Authorization header is present. Permitted explicitly in SecurityConfig's
 * fixed permitAll list — deliberately NOT relying on the SPA-fallback catch-all.
 */
@RestController
@RequestMapping("/api/v1/public/demo")
public class DemoStartController {

    private final DemoStartService demoStartService;

    public DemoStartController(DemoStartService demoStartService) {
        this.demoStartService = demoStartService;
    }

    @PostMapping("/start")
    public DemoStartResponse start(@RequestBody DemoStartRequest req, HttpServletRequest request) {
        return demoStartService.start(req, request.getRemoteAddr());
    }
}
