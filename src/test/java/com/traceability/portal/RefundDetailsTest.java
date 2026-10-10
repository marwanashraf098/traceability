package com.traceability.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Returns portal P2 — RefundDetails, the validation rules (no Spring):
 *   v1 Egyptian mobile: 010/011/012/015 + 8 digits; +20, 0020, spaces and dashes normalised; others refused
 *   v2 IBAN: "EG" + 27 digits with mod-97 = 1 (a one-digit change fails); plain account 6–20 digits
 *   v3 per method: bank (names ≤ 100), InstaPay (name@instapay or mobile), wallet (provider + mobile), cash
 *   v4 hint = "••••" + last 4; no PII in toString; JSON only carries the method's own fields
 */
class RefundDetailsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** The Wikipedia / CBE sample Egyptian IBAN. */
    static final String IBAN = "EG380019000500000000263180002";

    @Test
    void v1_egyptianMobile_variantsNormalised() {
        for (String ok : new String[]{"01012345678", "010 1234 5678", "+20 10 1234 5678", "+201012345678",
                "00201012345678", "010-1234-5678", "011 2345 6789", "01223456789", "01523456789"}) {
            assertThat(RefundDetails.normalizeMobile(ok)).as(ok).isPresent();
        }
        assertThat(RefundDetails.normalizeMobile("+20 10 1234 5678")).contains("01012345678");
        assertThat(RefundDetails.normalizeMobile("00201012345678")).contains("01012345678");
        for (String bad : new String[]{"0101234567", "010123456789", "01312345678", "02012345678", "1012345678",
                "+2110 1234 5678", "0101234567a", "", null}) {
            assertThat(RefundDetails.normalizeMobile(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }

    @Test
    void v2_iban_mod97_andAccountNumber() {
        assertThat(RefundDetails.validEgyptianIban(IBAN)).isTrue();
        assertThat(RefundDetails.validEgyptianIban("eg38 0019 0005 0000 0000 2631 8000 2")).isTrue();
        assertThat(RefundDetails.validEgyptianIban(withCheckDigits("0019000500000000263180009"))).isTrue();
        // One digit changed → the check fails.
        assertThat(RefundDetails.validEgyptianIban("EG380019000500000000263180003")).isFalse();
        assertThat(RefundDetails.validEgyptianIban("EG38001900050000000026318000")).isFalse();    // 26 digits
        assertThat(RefundDetails.validEgyptianIban("GB82WEST12345698765432")).isFalse();

        assertThat(bank("1234567890")).isPresent();
        assertThat(bank("123456")).isPresent();
        assertThat(bank("12345678901234567890")).isPresent();
        assertThat(bank("12345")).isEmpty();                     // too short
        assertThat(bank("123456789012345678901")).isEmpty();     // too long
        assertThat(bank("EG380019000500000000263180003")).isEmpty();  // looks like an IBAN, fails the check
        assertThat(bank(IBAN).get().account()).isEqualTo(IBAN);
        assertThat(bank("eg38 0019 0005 0000 0000 2631 8000 2").get().account()).isEqualTo(IBAN);
    }

    @Test
    void v3_perMethodRules() {
        // Bank transfer: holder + bank required, ≤ 100 characters.
        assertThat(RefundDetails.parse("bank_transfer", details("holderName", "Ahmed", "account", IBAN))).isEmpty();
        assertThat(RefundDetails.parse("bank_transfer", details("holderName", "x".repeat(101), "bankName", "CIB",
            "account", IBAN))).isEmpty();
        assertThat(RefundDetails.parse("bank_transfer", details("holderName", "  منى   عادل ", "bankName", "x".repeat(100),
            "account", IBAN)).get().holderName()).isEqualTo("منى عادل");
        // InstaPay: an address or an Egyptian mobile.
        assertThat(RefundDetails.parse("instapay", details("instapay", "Ahmed.K@InstaPay")).get().instapay())
            .isEqualTo("ahmed.k@instapay");
        assertThat(RefundDetails.parse("instapay", details("instapay", "+20 10 1234 5678")).get().instapay())
            .isEqualTo("01012345678");
        assertThat(RefundDetails.parse("instapay", details("instapay", "ahmed@gmail.com"))).isEmpty();
        assertThat(RefundDetails.parse("instapay", details("instapay", "0101234"))).isEmpty();
        // Wallet: a known provider + a mobile.
        assertThat(RefundDetails.parse("wallet", details("provider", "vodafone_cash", "walletNumber", "010 1234 5678"))
            .get().walletNumber()).isEqualTo("01012345678");
        for (String p : RefundDetails.WALLET_PROVIDERS) {
            assertThat(RefundDetails.parse("wallet", details("provider", p, "walletNumber", "01512345678"))).as(p).isPresent();
        }
        assertThat(RefundDetails.parse("wallet", details("provider", "paypal", "walletNumber", "01012345678"))).isEmpty();
        assertThat(RefundDetails.parse("wallet", details("provider", "we_pay", "walletNumber", "0150 123 45"))).isEmpty();
        // Cash: nothing needed; unknown methods refused.
        assertThat(RefundDetails.parse("cash", null).get().hasDetails()).isFalse();
        assertThat(RefundDetails.parse("crypto", details())).isEmpty();
        assertThat(RefundDetails.parse(null, details())).isEmpty();
    }

    @Test
    void v4_hint_toString_json() {
        assertThat(bank(IBAN).get().hint()).isEqualTo("••••0002");
        assertThat(RefundDetails.parse("wallet", details("provider", "we_pay", "walletNumber", "01012344521")).get().hint())
            .isEqualTo("••••4521");
        assertThat(RefundDetails.parse("instapay", details("instapay", "01012344521")).get().hint()).isEqualTo("••••4521");
        assertThat(RefundDetails.parse("instapay", details("instapay", "ahmed.k@instapay")).get().hint()).isEqualTo("••••ed.k");
        assertThat(RefundDetails.parse("cash", null).get().hint()).isNull();

        RefundDetails d = bank(IBAN).get();
        assertThat(d.toString()).isEqualTo("RefundDetails[method=bank_transfer]").doesNotContain(IBAN).doesNotContain("Mona");
        assertThat(d.toJson(mapper)).contains("\"account\":\"" + IBAN + "\"").doesNotContain("walletNumber").doesNotContain("instapay");
    }

    private java.util.Optional<RefundDetails> bank(String account) {
        return RefundDetails.parse("bank_transfer", details("holderName", "Mona Adel", "bankName", "CIB", "account", account));
    }

    private ObjectNode details(String... kv) {
        ObjectNode n = mapper.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) n.put(kv[i], kv[i + 1]);
        return n;
    }

    /** "EG" + ISO 13616 check digits + bban. */
    static String withCheckDigits(String bban) {
        String digits = bban + "1416" + "00";   // E=14, G=16
        int check = 98 - new BigInteger(digits).mod(BigInteger.valueOf(97)).intValue();
        return "EG" + String.format("%02d", check) + bban;
    }
}
