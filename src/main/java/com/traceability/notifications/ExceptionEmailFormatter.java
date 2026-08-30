package com.traceability.notifications;

import java.util.Map;

/**
 * Shared, purely-cosmetic helpers for the exception-notification emails
 * (ExceptionImmediateAlertJob, ExceptionDigestJob). No detection logic lives here —
 * both jobs source their exception lists exclusively from ExceptionService.detectAllOpen().
 */
final class ExceptionEmailFormatter {

    private ExceptionEmailFormatter() {}

    /** Prefixes a relative actionUrl with the app base URL; passes an absolute URL through as-is. */
    static String resolveUrl(String actionUrl, String appUrl) {
        if (actionUrl == null || actionUrl.isBlank()) return appUrl;
        if (actionUrl.startsWith("http://") || actionUrl.startsWith("https://")) return actionUrl;
        return appUrl + actionUrl;
    }

    static String severityLabelEn(String severity) {
        return severity == null ? "" : severity;
    }

    static String severityLabelAr(String severity) {
        if (severity == null) return "";
        return switch (severity) {
            case "CRITICAL" -> "حرجة";
            case "HIGH"     -> "مرتفعة";
            case "MEDIUM"   -> "متوسطة";
            case "LOW"      -> "منخفضة";
            default         -> severity;
        };
    }

    /** Escapes text pulled from operator-entered fields (e.g. hold_reason) before HTML embedding. */
    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** One EN <li> row for a single exception item. */
    static String itemRowEn(Map<String, Object> item, String appUrl) {
        String severity = (String) item.get("severity");
        String desc = escapeHtml(String.valueOf(item.get("descriptionEn")));
        String url = resolveUrl((String) item.get("actionUrl"), appUrl);
        return "<li style=\"margin-bottom:8px;\"><strong>[" + severityLabelEn(severity) + "]</strong> "
                + desc + " &mdash; <a href=\"" + url + "\" style=\"color:#2563EB;\">View</a></li>";
    }

    /** One AR <li> row (dir=rtl context assumed by the caller's wrapping block) for a single item. */
    static String itemRowAr(Map<String, Object> item, String appUrl) {
        String severity = (String) item.get("severity");
        String desc = escapeHtml(String.valueOf(item.get("descriptionAr")));
        String url = resolveUrl((String) item.get("actionUrl"), appUrl);
        return "<li style=\"margin-bottom:8px;\"><strong>[" + severityLabelAr(severity) + "]</strong> "
                + desc + " &mdash; <a href=\"" + url + "\" style=\"color:#2563EB;\">عرض</a></li>";
    }
}
