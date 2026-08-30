package com.traceability.notifications;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders the two approved exception-notification email bodies (immediate alert,
 * daily digest) from their classpath HTML templates (emails/exception-alert.html,
 * emails/exception-digest.html). Purely cosmetic — no detection, ledger, dedup, or
 * scheduling logic lives here; ExceptionImmediateAlertJob / ExceptionDigestJob own all
 * of that and just call in with already-computed data.
 *
 * Each template has TWO identically-delimited <!-- EXCEPTION_ROW_START/END --> regions:
 * the FIRST (positionally) is the English/LTR row, the SECOND is the Arabic/RTL row.
 * They are extracted and filled independently — descriptionEn feeds the first,AR the
 * second — never assumed to share one row shape.
 *
 * Item-derived text (descriptions, URLs) is substituted into its row template ONLY —
 * never re-passed through the template-wide scalar substitution pass — so a description
 * that happens to contain a literal "{{...}}" sequence (operator free-text) can't corrupt
 * or be corrupted by the outer token fill.
 */
final class ExceptionEmailFormatter {

    private static final String ALERT_TEMPLATE_PATH  = "emails/exception-alert.html";
    private static final String DIGEST_TEMPLATE_PATH = "emails/exception-digest.html";

    private static final String ROW_START = "<!-- EXCEPTION_ROW_START -->";
    private static final String ROW_END   = "<!-- EXCEPTION_ROW_END -->";

    private static final String EMPTY_NEW_EN =
            "<tr><td style=\"padding:12px 0; font-family:'Geist', -apple-system, 'Segoe UI', " +
            "Roboto, Helvetica, Arial, sans-serif; font-size:14px; line-height:22px; color:#9CA3AF;\">" +
            "No new exceptions since your last summary.</td></tr>";
    private static final String EMPTY_NEW_AR =
            "<tr><td dir=\"rtl\" style=\"padding:12px 0; text-align:right; font-family:'Cairo', " +
            "Tahoma, Arial, sans-serif; font-size:14px; line-height:26px; color:#9CA3AF;\">" +
            "لا توجد استثناءات جديدة منذ آخر ملخص.</td></tr>";

    private static volatile String alertTemplateCache;
    private static volatile String digestTemplateCache;

    private ExceptionEmailFormatter() {}

    // ── Public API ───────────────────────────────────────────────────────────

    /** The immediate-alert body. Never called with an empty list (the job skips sending then). */
    static String buildAlertBody(List<Map<String, Object>> items, String appUrl) {
        Regions regions = splitRegions(loadAlertTemplate());

        String rowsEn = joinRows(regions.rowTemplateEn(), items, appUrl, true);
        String rowsAr = joinRows(regions.rowTemplateAr(), items, appUrl, false);

        Map<String, String> scalars = Map.of(
                "{{ALERT_COUNT}}", String.valueOf(items.size()),
                "{{APP_URL}}", appUrl);

        return assemble(regions, scalars, rowsEn, rowsAr);
    }

    /**
     * The daily digest body. {@code digestDate} is caller-supplied (Africa/Cairo, "d MMM yyyy") —
     * this formatter never recomputes a server-local date. An empty {@code newItems} renders a
     * single muted "no new exceptions" line in both languages instead of an empty section.
     */
    static String buildDigestBody(List<Map<String, Object>> newItems,
                                   int countTotal, int countCritical, int countWarning,
                                   String digestDate, String appUrl) {
        Regions regions = splitRegions(loadDigestTemplate());

        String rowsEn, rowsAr;
        if (newItems.isEmpty()) {
            rowsEn = EMPTY_NEW_EN;
            rowsAr = EMPTY_NEW_AR;
        } else {
            rowsEn = joinRows(regions.rowTemplateEn(), newItems, appUrl, true);
            rowsAr = joinRows(regions.rowTemplateAr(), newItems, appUrl, false);
        }

        Map<String, String> scalars = Map.of(
                "{{DIGEST_DATE}}", digestDate,
                "{{COUNT_TOTAL}}", String.valueOf(countTotal),
                "{{COUNT_CRITICAL}}", String.valueOf(countCritical),
                "{{COUNT_WARNING}}", String.valueOf(countWarning),
                "{{APP_URL}}", appUrl);

        return assemble(regions, scalars, rowsEn, rowsAr);
    }

    // ── Template loading (cached, like WelcomeEmailJob's templateCache) ────────

    private static String loadAlertTemplate() {
        String t = alertTemplateCache;
        if (t == null) {
            synchronized (ExceptionEmailFormatter.class) {
                t = alertTemplateCache;
                if (t == null) {
                    t = readClasspathResource(ALERT_TEMPLATE_PATH);
                    alertTemplateCache = t;
                }
            }
        }
        return t;
    }

    private static String loadDigestTemplate() {
        String t = digestTemplateCache;
        if (t == null) {
            synchronized (ExceptionEmailFormatter.class) {
                t = digestTemplateCache;
                if (t == null) {
                    t = readClasspathResource(DIGEST_TEMPLATE_PATH);
                    digestTemplateCache = t;
                }
            }
        }
        return t;
    }

    private static String readClasspathResource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + path, e);
        }
    }

    // ── Region splitting / row rendering ────────────────────────────────────

    /** prefix + [EN row template] + between + [AR row template] + suffix, split from one template. */
    private record Regions(String prefix, String rowTemplateEn, String between, String rowTemplateAr, String suffix) {}

    private static Regions splitRegions(String template) {
        int[] region1 = findRegion(template, 0);            // English — first occurrence
        int[] region2 = findRegion(template, region1[1]);   // Arabic — second occurrence

        String prefix  = template.substring(0, region1[0]);
        String rowEn   = template.substring(region1[0] + ROW_START.length(), region1[1] - ROW_END.length());
        String between = template.substring(region1[1], region2[0]);
        String rowAr   = template.substring(region2[0] + ROW_START.length(), region2[1] - ROW_END.length());
        String suffix  = template.substring(region2[1]);

        return new Regions(prefix, rowEn, between, rowAr, suffix);
    }

    /** One START..END delimited region searched from {@code searchFrom}. Returns [start, endExclusive]. */
    private static int[] findRegion(String template, int searchFrom) {
        int start = template.indexOf(ROW_START, searchFrom);
        if (start < 0) throw new IllegalStateException("EXCEPTION_ROW_START not found from index " + searchFrom);
        int end = template.indexOf(ROW_END, start);
        if (end < 0) throw new IllegalStateException("EXCEPTION_ROW_END not found after index " + start);
        return new int[]{start, end + ROW_END.length()};
    }

    private static String assemble(Regions r, Map<String, String> scalars, String rowsEn, String rowsAr) {
        String prefix  = applyScalars(r.prefix(), scalars);
        String between = applyScalars(r.between(), scalars);
        String suffix  = applyScalars(r.suffix(), scalars);
        return prefix + rowsEn + between + rowsAr + suffix;
    }

    private static String applyScalars(String s, Map<String, String> scalars) {
        for (Map.Entry<String, String> e : scalars.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        return s;
    }

    private static String joinRows(String rowTemplate, List<Map<String, Object>> items, String appUrl, boolean english) {
        return items.stream()
                .map(item -> fillRow(rowTemplate, item, appUrl, english))
                .collect(Collectors.joining());
    }

    private static String fillRow(String rowTemplate, Map<String, Object> item, String appUrl, boolean english) {
        String severity = (String) item.get("severity");
        String desc = escapeHtml(String.valueOf(english ? item.get("descriptionEn") : item.get("descriptionAr")));
        String url = resolveUrl((String) item.get("actionUrl"), appUrl);
        String label = english ? severityLabelEn(severity) : severityLabelAr(severity);
        return rowTemplate
                .replace("{{ITEM_SEVERITY_BG}}", severityBg(severity))
                .replace("{{ITEM_SEVERITY_LABEL}}", label)
                .replace("{{ITEM_DESC}}", desc)
                .replace("{{ITEM_URL}}", url);
    }

    // ── Severity map — single source, all four tiers (the digest itemizes every severity) ──

    private static String severityBg(String severity) {
        if (severity == null) return "#6B7280";
        return switch (severity) {
            case "CRITICAL" -> "#DC2626";
            case "HIGH"     -> "#D97706";
            case "MEDIUM"   -> "#6B7280";
            case "LOW"      -> "#9CA3AF";
            default         -> "#6B7280";
        };
    }

    static String severityLabelEn(String severity) {
        return severity == null ? "" : severity;
    }

    static String severityLabelAr(String severity) {
        if (severity == null) return "";
        return switch (severity) {
            case "CRITICAL" -> "حرجة";
            case "HIGH"     -> "عالية";
            case "MEDIUM"   -> "متوسطة";
            case "LOW"      -> "منخفضة";
            default         -> severity;
        };
    }

    // ── Shared utilities ─────────────────────────────────────────────────────

    /** Prefixes a relative actionUrl with the app base URL; passes an absolute URL through as-is. */
    static String resolveUrl(String actionUrl, String appUrl) {
        if (actionUrl == null || actionUrl.isBlank()) return appUrl;
        if (actionUrl.startsWith("http://") || actionUrl.startsWith("https://")) return actionUrl;
        return appUrl + actionUrl;
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
}
