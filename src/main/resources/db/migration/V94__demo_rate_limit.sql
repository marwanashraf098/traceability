-- ============================================================
-- V94 — Demo public-endpoint rate limiting (12th SECURITY DEFINER hatch)
-- ============================================================
-- FR-DEMO Day 2: POST /api/v1/public/demo/start is unauthenticated and
-- provisioning-adjacent (it mints a JWT) — it must be rate-limited before it
-- ever reaches DemoSeeder. app_user has INSERT-only on demo_leads (V93);
-- reading counts to make the rate-limit decision needs the same pre-session
-- DEFINER-read treatment as check_password_reset_throttle (V86, hatch #8) —
-- the caller (an anonymous visitor) has no tenant context, and demo_leads
-- carries no tenant_id to scope by in the first place.
--
-- Returns two independent counts; the threshold comparison (5/hour per IP,
-- 20/90-min global — chosen in DemoStartService, not here) stays in Java,
-- same split as isThrottled()'s 60-second debounce comparison. LANGUAGE sql,
-- read-only, no atomicity concerns — mirrors hatch #8 exactly.
CREATE OR REPLACE FUNCTION check_demo_rate_limit(p_ip inet)
RETURNS TABLE(ip_count bigint, global_count bigint)
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT
        (SELECT COUNT(*) FROM demo_leads
            WHERE ip = p_ip AND created_at > now() - interval '1 hour') AS ip_count,
        (SELECT COUNT(*) FROM demo_leads
            WHERE created_at > now() - interval '90 minutes') AS global_count;
$$;

REVOKE ALL ON FUNCTION check_demo_rate_limit(inet) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION check_demo_rate_limit(inet) TO app_user;
