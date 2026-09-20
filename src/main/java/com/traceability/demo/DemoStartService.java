package com.traceability.demo;

import com.traceability.identity.JwtService;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * FR-DEMO Day 2 — POST /api/v1/public/demo/start orchestration: validate → rate-limit →
 * persist lead → mint demo JWT → enqueue lead notification.
 *
 * Deliberately does NOT touch TenantContext for the lead insert or the rate-limit check —
 * demo_leads carries no tenant_id (V93) and check_demo_rate_limit is a pre-session DEFINER
 * read, same shape as PasswordResetService.isThrottled()/check_password_reset_throttle.
 */
@Service
public class DemoStartService {

    /** Reject the 6th+ request from the same IP within the rolling hour. */
    private static final int IP_LIMIT_PER_HOUR = 5;
    /** Reject once 20 leads have been captured in the last 90 minutes, tenant-wide — a proxy
     *  for "concurrently active demo sessions" since JWTs are stateless and carry no session row. */
    private static final int GLOBAL_LIMIT_PER_90_MIN = 20;
    private static final Duration DEMO_TOKEN_TTL = Duration.ofMinutes(90);

    private final JdbcTemplate jdbc;
    private final DemoSeeder demoSeeder;
    private final JwtService jwtService;
    private final JobScheduler jobScheduler;
    private final DemoLeadNotificationJob demoLeadNotificationJob;

    public DemoStartService(JdbcTemplate jdbc,
                             DemoSeeder demoSeeder,
                             JwtService jwtService,
                             JobScheduler jobScheduler,
                             DemoLeadNotificationJob demoLeadNotificationJob) {
        this.jdbc = jdbc;
        this.demoSeeder = demoSeeder;
        this.jwtService = jwtService;
        this.jobScheduler = jobScheduler;
        this.demoLeadNotificationJob = demoLeadNotificationJob;
    }

    public DemoStartResponse start(DemoStartRequest req, String ip) {
        validate(req);
        checkRateLimit(ip);
        persistLead(req, ip);

        // Self-heal: DemoBootstrapStartupListener already does this on ApplicationReadyEvent,
        // but this call is what protects the first real visitor even if that listener were
        // ever skipped (e.g. a startup ordering issue) — ensureBootstrapped() is existence-
        // checked, so this is a no-op read on every call after the very first.
        demoSeeder.ensureBootstrapped();
        UUID ownerId = demoSeeder.resolveOwnerId();
        String accessToken = jwtService.issueAccessToken(
                ownerId, DemoSeeder.DEMO_TENANT_ID, "owner", DEMO_TOKEN_TTL);

        jobScheduler.enqueue(() ->
                demoLeadNotificationJob.run(req.name(), req.email(), req.phone(), ip));

        return new DemoStartResponse(accessToken, "/overview");
    }

    // ---- validation (manual, matches SignupRequest/AuthService convention) ----

    private void validate(DemoStartRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw DemoException.inputInvalid();
        }
        if (req.email() == null || !looksLikeEmail(req.email())) {
            throw DemoException.inputInvalid();
        }
        // Phone is captured UNVERIFIED by design (locked decision — OTP/E.164 normalization
        // would reintroduce the friction the demo is built to avoid). Bare non-blank only —
        // do NOT call AuthService.normalizeEgyptianPhoneToE164() here.
        if (req.phone() == null || req.phone().isBlank()) {
            throw DemoException.inputInvalid();
        }
        if (!req.consent()) {
            throw DemoException.inputInvalid();
        }
    }

    private static boolean looksLikeEmail(String email) {
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        return at > 0 && at < trimmed.length() - 1 && trimmed.indexOf('.', at) > at;
    }

    // ---- rate limiting ----

    private void checkRateLimit(String ip) {
        Map<String, Object> counts = jdbc.queryForMap(
                "SELECT ip_count, global_count FROM check_demo_rate_limit(?::inet)", ip);
        long ipCount = ((Number) counts.get("ip_count")).longValue();
        long globalCount = ((Number) counts.get("global_count")).longValue();
        if (ipCount >= IP_LIMIT_PER_HOUR || globalCount >= GLOBAL_LIMIT_PER_90_MIN) {
            throw DemoException.rateLimited();
        }
    }

    // ---- lead persistence ----

    /**
     * Plain INSERT on the ordinary app_user connection, no TenantContext — demo_leads is not
     * under RLS and app_user has INSERT-only (V93), same access shape as
     * MagicLinkService.issueMagicLink()'s write to magic_link_tokens.
     */
    private void persistLead(DemoStartRequest req, String ip) {
        jdbc.update(
                "INSERT INTO demo_leads (name, email, phone, ip, consented_at) " +
                "VALUES (?, ?, ?, ?::inet, now())",
                req.name(), req.email(), req.phone(), ip);
    }
}
