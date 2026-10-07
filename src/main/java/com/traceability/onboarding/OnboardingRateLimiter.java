package com.traceability.onboarding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Build D: per-shop and per-IP limits on /api/v1/embedded/onboarding/** (embedded_onboarding_attempts,
 * V147 — no tenant, no PII: the IP is stored only as an HMAC). Every accepted call is recorded; a
 * refused one is not, so a lockout lifts on its own an hour after the last accepted attempt. Rows
 * older than 24 hours are deleted nightly by OnboardingPurgeJob (app_user has no DELETE here).
 */
@Component
public class OnboardingRateLimiter {

    static final int SHOP_MAX_PER_HOUR = 30;
    static final int IP_MAX_PER_HOUR   = 60;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final byte[] ipKey;

    public OnboardingRateLimiter(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                 @Value("${traced.onboarding.ip-hash-key:${shopify.client-secret}}") String ipKey) {
        this.jdbc  = jdbc;
        this.tx    = new TransactionTemplate(txm);
        this.ipKey = ipKey.getBytes(StandardCharsets.UTF_8);
    }

    /** @throws OnboardingException RATE_LIMITED (429) when the shop or the IP is over its hourly limit. */
    public void checkAndRecord(String shop, String clientIp) {
        String shopBucket = "shop:" + shop;
        String ipBucket   = "ip:" + hmac(clientIp == null ? "" : clientIp);
        tx.executeWithoutResult(s -> {
            if (recent(shopBucket) >= SHOP_MAX_PER_HOUR || recent(ipBucket) >= IP_MAX_PER_HOUR) {
                throw OnboardingException.rateLimited();
            }
            jdbc.update("INSERT INTO embedded_onboarding_attempts (bucket) VALUES (?), (?)", shopBucket, ipBucket);
        });
    }

    private int recent(String bucket) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM embedded_onboarding_attempts " +
            "WHERE bucket = ? AND attempted_at > now() - interval '1 hour'", Integer.class, bucket);
        return n == null ? 0 : n;
    }

    String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(ipKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
