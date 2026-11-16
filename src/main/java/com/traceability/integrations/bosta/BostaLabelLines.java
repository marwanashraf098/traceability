package com.traceability.integrations.bosta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bug 2 / 3 (approved 2026-11-16) — THE parser of the item lines Bosta prints on a waybill label (its package
 * description), so Scan returns can say what is actually in a parcel. Pure: no DB, no Bosta call.
 *
 * Two formats, both seen on prod (BROEK):
 *   - a return pickup (type 25 {@code returnSpecs.packageDetails.description}, also an exchange's returnSpecs):
 *     {@code "x1 <name> - <size> - SKU: <sku> // x1 <name> - … - SKU: <sku>"};
 *   - a forward / returned-to-sender leg ({@code specs.packageDetails.description}, the Shopify plugin's text):
 *     one line per item, {@code "<name> - <size> x N (<sku>)"}.
 *
 * Bosta's {@code itemsCount} is NEVER used — it disagrees with the label (4226201959: specs.itemsCount 1, the label 3
 * items, 3 arrived). Every non-blank segment must parse, in one format; anything else (free text, a missing SKU, a mix)
 * is unparseable → {@code null}, and callers keep their pre-label behaviour. SKUs are kept exactly as printed (trimmed)
 * and matched to the tenant's variants by exact SKU — never fuzzy. The same SKU on several lines is summed.
 */
public final class BostaLabelLines {

    private BostaLabelLines() {}

    public record Line(String sku, int quantity, String name) {}

    /** The parsed label: its lines (first appearance order) and the item total. */
    public record Label(List<Line> lines) {
        public int total() { return lines.stream().mapToInt(Line::quantity).sum(); }
        public int quantityOf(String sku) {
            if (sku == null) return 0;
            String key = sku.trim();
            return lines.stream().filter(l -> l.sku().equals(key)).mapToInt(Line::quantity).sum();
        }
    }

    // "x1 Boxy Oversized Crewneck – Burgundy - M - SKU: BRK-RGC-2627-BRG-M"
    private static final Pattern RETURN_LINE = Pattern.compile("^x\\s*(\\d+)\\s+(.+?)\\s*-\\s*SKU:\\s*(\\S+)$");
    // "Gray Relaxed Fit Denim Jeans - 30 x 1 (BROEK-Gray-RFD-1)"
    private static final Pattern FORWARD_LINE = Pattern.compile("^(.+?)\\s+x\\s*(\\d+)\\s*\\(([^()\\s]+)\\)$");

    /** The label's lines, or null when the description isn't a label Traced can read. */
    public static Label parse(String description) {
        if (description == null || description.isBlank()) return null;
        Label label = description.contains("SKU:") ? parse(description.split("//"), RETURN_LINE, 1, 2, 3)
            : parse(description.split("\\R"), FORWARD_LINE, 2, 1, 3);
        return label == null || label.lines().isEmpty() ? null : label;
    }

    private static Label parse(String[] segments, Pattern p, int qtyGroup, int nameGroup, int skuGroup) {
        Map<String, Line> bySku = new LinkedHashMap<>();
        for (String raw : segments) {
            String seg = raw.strip();
            if (seg.isEmpty()) continue;
            Matcher m = p.matcher(seg);
            if (!m.matches()) return null;
            int qty;
            try {
                qty = Integer.parseInt(m.group(qtyGroup));
            } catch (NumberFormatException e) {
                return null;
            }
            if (qty <= 0) return null;
            String sku = m.group(skuGroup).trim();
            Line prev = bySku.get(sku);
            bySku.put(sku, prev == null ? new Line(sku, qty, m.group(nameGroup).strip())
                : new Line(sku, prev.quantity() + qty, prev.name()));
        }
        return new Label(Collections.unmodifiableList(new ArrayList<>(bySku.values())));
    }
}
