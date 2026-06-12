package com.traceability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Test-only endpoint: returns the current GUC and visible location count. */
@RestController
@RequestMapping("/api/v1/test")
class TenantProbeController {

    private final TenantProbeService svc;

    TenantProbeController(TenantProbeService svc) {
        this.svc = svc;
    }

    @GetMapping("/probe")
    public Map<String, Object> probe() {
        return svc.probe();
    }
}
