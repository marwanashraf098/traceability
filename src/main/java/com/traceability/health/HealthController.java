package com.traceability.health;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        String dbStatus;
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            dbStatus = "ok";
        } catch (Exception e) {
            dbStatus = "error";
        }
        return Map.of(
            "status",    "ok",
            "db",        dbStatus,
            "timestamp", Instant.now().toString()
        );
    }
}
