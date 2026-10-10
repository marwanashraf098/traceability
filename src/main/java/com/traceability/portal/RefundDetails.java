package com.traceability.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Returns portal P2 — the refund method a customer chose and the details it needs, validated and
 * normalised. THE rules (the portal mirrors them):
 * <ul>
 *   <li>Egyptian mobile: spaces / dashes / dots removed, "+20" or "0020" → "0", then 01[0125] + 8 digits;</li>
 *   <li>bank account: an Egyptian IBAN ("EG" + 27 digits, mod-97 = 1) or a plain account number
 *       (6–20 digits); holder and bank names 1–100 characters;</li>
 *   <li>InstaPay: name@instapay, or an Egyptian mobile;</li>
 *   <li>wallet: one of {@link #WALLET_PROVIDERS} + an Egyptian mobile;</li>
 *   <li>cash: no details.</li>
 * </ul>
 * The details are customer PII: never logged, never returned by a public endpoint, never copied
 * into return_refunds.
 */
public record RefundDetails(String method, String holderName, String bankName, String account,
                            String instapay, String provider, String walletNumber) {

    public static final List<String> METHODS = List.of("bank_transfer", "instapay", "wallet", "cash");
    public static final List<String> WALLET_PROVIDERS = List.of("vodafone_cash", "orange_cash", "e_and_cash", "we_pay");
    static final int NAME_MAX = 100;

    private static final Pattern MOBILE = Pattern.compile("^01[0125]\\d{8}$");
    private static final Pattern IBAN_EG = Pattern.compile("^EG\\d{27}$");
    private static final Pattern ACCOUNT = Pattern.compile("^\\d{6,20}$");
    private static final Pattern INSTAPAY = Pattern.compile("^[a-z0-9._-]{1,64}@instapay$");

    /** "01012345678" from any of: 010 1234 5678, +20 10 1234 5678, 0020101…, 01012345678. Empty when not one. */
    public static Optional<String> normalizeMobile(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.replaceAll("[\\s\\-.()]", "");
        if (s.startsWith("+20")) s = "0" + s.substring(3);
        else if (s.startsWith("0020")) s = "0" + s.substring(4);
        return MOBILE.matcher(s).matches() ? Optional.of(s) : Optional.empty();
    }

    /** True for "EG" + 27 digits whose ISO 13616 mod-97 check is 1. Spaces allowed, any case. */
    public static boolean validEgyptianIban(String raw) {
        if (raw == null) return false;
        String s = raw.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!IBAN_EG.matcher(s).matches()) return false;
        String rearranged = s.substring(4) + s.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isLetter(c) ? String.valueOf(c - 'A' + 10) : String.valueOf(c));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    /**
     * Validated and normalised, or empty when anything is wrong. {@code details} may be null for
     * cash. Unknown method → empty.
     */
    public static Optional<RefundDetails> parse(String method, JsonNode details) {
        if (method == null || !METHODS.contains(method)) return Optional.empty();
        JsonNode d = details == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : details;
        switch (method) {
            case "cash" -> {
                return Optional.of(new RefundDetails(method, null, null, null, null, null, null));
            }
            case "bank_transfer" -> {
                String holder = name(text(d, "holderName"));
                String bank = name(text(d, "bankName"));
                String account = account(text(d, "account"));
                if (holder == null || bank == null || account == null) return Optional.empty();
                return Optional.of(new RefundDetails(method, holder, bank, account, null, null, null));
            }
            case "instapay" -> {
                String v = text(d, "instapay");
                if (v == null) return Optional.empty();
                String addr = v.trim().toLowerCase(Locale.ROOT);
                if (INSTAPAY.matcher(addr).matches()) {
                    return Optional.of(new RefundDetails(method, null, null, null, addr, null, null));
                }
                return normalizeMobile(v).map(m -> new RefundDetails(method, null, null, null, m, null, null));
            }
            case "wallet" -> {
                String provider = text(d, "provider");
                if (provider == null || !WALLET_PROVIDERS.contains(provider)) return Optional.empty();
                return normalizeMobile(text(d, "walletNumber"))
                    .map(m -> new RefundDetails(method, null, null, null, null, provider, m));
            }
            default -> { return Optional.empty(); }
        }
    }

    /** A valid IBAN (stored without spaces, upper case) or a plain account number; null otherwise. */
    private static String account(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (s.startsWith("EG")) return validEgyptianIban(s) ? s : null;
        return ACCOUNT.matcher(s).matches() ? s : null;
    }

    private static String name(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replaceAll("\\s+", " ");
        return s.isEmpty() || s.length() > NAME_MAX ? null : s;
    }

    private static String text(JsonNode d, String field) {
        JsonNode n = d.get(field);
        return n == null || n.isNull() || !n.isTextual() ? null : n.asText();
    }

    /** True when there is something to encrypt (every method but cash). */
    public boolean hasDetails() {
        return !"cash".equals(method);
    }

    /**
     * "••••" + the last 4 characters of the identifying value — the account / IBAN, the wallet
     * number, the InstaPay phone, or (for a name@instapay address) the last 4 of its name part.
     * Null for cash.
     */
    public String hint() {
        String v = switch (method) {
            case "bank_transfer" -> account;
            case "wallet" -> walletNumber;
            case "instapay" -> instapay.endsWith("@instapay") ? instapay.substring(0, instapay.indexOf('@')) : instapay;
            default -> null;
        };
        if (v == null) return null;
        return "••••" + (v.length() <= 4 ? v : v.substring(v.length() - 4));
    }

    /** The blob that is encrypted — only the fields this method has. */
    public String toJson(ObjectMapper mapper) {
        ObjectNode o = mapper.createObjectNode();
        o.put("method", method);
        if (holderName != null) o.put("holderName", holderName);
        if (bankName != null) o.put("bankName", bankName);
        if (account != null) o.put("account", account);
        if (instapay != null) o.put("instapay", instapay);
        if (provider != null) o.put("provider", provider);
        if (walletNumber != null) o.put("walletNumber", walletNumber);
        return o.toString();
    }

    /** No PII in toString — a record's default would print every field into any log line. */
    @Override
    public String toString() {
        return "RefundDetails[method=" + method + "]";
    }
}
