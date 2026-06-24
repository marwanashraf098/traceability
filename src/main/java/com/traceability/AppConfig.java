package com.traceability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class AppConfig {

    /**
     * Application-wide clock pinned to Africa/Cairo (UTC+2 year-round; Egypt
     * dropped DST in 2011). The production server runs in Europe/Germany, so
     * business-logic date decisions must NOT rely on JVM default TZ.
     *
     * Single-country pilot: hardcoded Cairo is correct. When multi-country
     * support is needed, replace with a per-request tenant TZ lookup:
     *   ZoneId.of(jdbc.queryForObject("SELECT timezone FROM tenants WHERE id=?",
     *              String.class, TenantContext.require()))
     */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Africa/Cairo"));
    }
}
