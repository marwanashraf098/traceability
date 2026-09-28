package com.traceability.identity.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

/**
 * Meta ad attribution captured at signup (tenant_ad_attribution, V116). Built only through
 * {@link #from}: every field is validated and capped, and anything malformed becomes null —
 * attribution data must never fail a signup.
 *
 * client_ip / client_user_agent are kept for later server-side matching of the
 * "ShopifyConnected" event. Retention (not built yet): clear both once that event has been
 * sent (connected_event_sent_at) or 90 days after captured_at, whichever comes first.
 */
public record SignupAttribution(
        String fbp, String fbc, String fbclid,
        String utmSource, String utmMedium, String utmCampaign, String utmTerm, String utmContent,
        String clientIp, String clientUserAgent) {

    // _fbp = fb.<subdomain index>.<creation ms>.<random>
    private static final Pattern FBP = Pattern.compile("^fb\\.\\d\\.\\d{10,13}\\.\\d{1,25}$");
    // _fbc = fb.<subdomain index>.<creation ms>.<fbclid>; lenient on the fbclid tail.
    private static final Pattern FBC = Pattern.compile("^fb\\.\\d\\.\\d{10,13}\\.[A-Za-z0-9_\\-.]{1,500}$");
    private static final Pattern FBCLID = Pattern.compile("^[A-Za-z0-9_\\-]{1,500}$");
    private static final Pattern IP = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final int UTM_MAX = 200;
    private static final int UA_MAX = 512;

    /** Null when there is no ad identifier or UTM at all — nothing worth storing. */
    public static SignupAttribution from(JsonNode body, String clientIp, String userAgent) {
        JsonNode b = (body != null && body.isObject()) ? body : null;
        SignupAttribution a = new SignupAttribution(
                matching(text(b, "fbp"), FBP),
                matching(text(b, "fbc"), FBC),
                matching(text(b, "fbclid"), FBCLID),
                free(text(b, "utmSource"), UTM_MAX),
                free(text(b, "utmMedium"), UTM_MAX),
                free(text(b, "utmCampaign"), UTM_MAX),
                free(text(b, "utmTerm"), UTM_MAX),
                free(text(b, "utmContent"), UTM_MAX),
                matching(clientIp, IP),
                free(userAgent, UA_MAX));
        return a.hasAdSignal() ? a : null;
    }

    public boolean hasAdSignal() {
        return fbp != null || fbc != null || fbclid != null || utmSource != null || utmMedium != null
                || utmCampaign != null || utmTerm != null || utmContent != null;
    }

    private static String text(JsonNode b, String field) {
        if (b == null) return null;
        JsonNode v = b.get(field);
        return (v != null && v.isTextual()) ? v.asText() : null;
    }

    private static String matching(String v, Pattern p) {
        if (v == null) return null;
        String t = v.trim();
        return p.matcher(t).matches() ? t : null;
    }

    private static String free(String v, int max) {
        if (v == null) return null;
        String t = CONTROL.matcher(v).replaceAll("").trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
