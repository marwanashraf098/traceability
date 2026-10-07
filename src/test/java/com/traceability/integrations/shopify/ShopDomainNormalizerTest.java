package com.traceability.integrations.shopify;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** "Find your store" — every accepted input shape, and what is refused. Pure string work, no network. */
class ShopDomainNormalizerTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
        // (a) .myshopify.com addresses
        "abc123.myshopify.com                                  | abc123.myshopify.com | myshopify",
        "ABC123.MyShopify.COM                                  | abc123.myshopify.com | myshopify",
        "https://abc123.myshopify.com/                         | abc123.myshopify.com | myshopify",
        "HTTP://Abc123.myshopify.com                           | abc123.myshopify.com | myshopify",
        "abc123.myshopify.com.                                 | abc123.myshopify.com | myshopify",
        "https://abc123.myshopify.com/products/shirt?x=1       | abc123.myshopify.com | myshopify",
        "j90uuk-yz.myshopify.com                               | j90uuk-yz.myshopify.com | myshopify",
        "'  abc123 . myshopify . com  '                        | abc123.myshopify.com | myshopify",
        // (b) admin links
        "admin.shopify.com/store/abc123                        | abc123.myshopify.com | admin_link",
        "https://admin.shopify.com/store/abc123/orders?inContextTimeframe=today | abc123.myshopify.com | admin_link",
        "https://ADMIN.shopify.com/store/ABC123/                | abc123.myshopify.com | admin_link",
        "admin.shopify.com/store/abc123#settings               | abc123.myshopify.com | admin_link",
        "https://abc123.myshopify.com/admin                    | abc123.myshopify.com | admin_link",
        "abc123.myshopify.com/admin/orders/123                 | abc123.myshopify.com | admin_link",
    })
    void accepted(String input, String shop, String source) {
        ShopDomainNormalizer.Result r = ShopDomainNormalizer.normalize(input);
        assertThat(r.shopDomain()).isEqualTo(shop);
        assertThat(r.source().wire()).isEqualTo(source);
        assertThat(ShopDomainNormalizer.isShopDomain(r.shopDomain())).isTrue();
    }

    @org.junit.jupiter.api.Test
    void invisibleCharacters_areStripped() {
        // zero-width space, zero-width joiner, BOM, right-to-left mark, soft hyphen, no-break space, line break
        String pasted = "​abc‍123﻿.my‏shopify­.com /\n";
        assertThat(ShopDomainNormalizer.normalize(pasted).shopDomain()).isEqualTo("abc123.myshopify.com");
        assertThat(ShopDomainNormalizer.normalize("⁦admin.shopify.com/store/abc123⁩").shopDomain())
            .isEqualTo("abc123.myshopify.com");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "", "   ", "my store abc", "thesnouts.com", "https://www.allbirds.com", "shopify.com",
        "myshopify.com", ".myshopify.com", "abc123.myshopify.co", "abc123.myshopify.com.evil.com",
        "evil.com/abc123.myshopify.com", "abc123.myshopify.com@evil.com", "user@abc123.myshopify.com",
        "abc123.myshopify.com:8443", "admin.shopify.com", "admin.shopify.com/store/", "admin.shopify.com/settings",
        "admin.shopify.com.evil.com/store/abc123", "-abc.myshopify.com", "abc-.myshopify.com", "ab_c.myshopify.com",
        "javascript:alert(1)", "ftp://abc123.myshopify.com/x/../../evil",
    })
    void refused(String input) {
        if (input.startsWith("ftp://")) {   // any scheme is stripped — the host still has to be the store
            assertThat(ShopDomainNormalizer.normalize(input).shopDomain()).isEqualTo("abc123.myshopify.com");
            return;
        }
        assertThatThrownBy(() -> ShopDomainNormalizer.normalize(input))
            .isInstanceOf(ShopDomainNormalizer.NotShopifyAddress.class);
    }

    @org.junit.jupiter.api.Test
    void nullAndOverlongHandle_refused() {
        assertThatThrownBy(() -> ShopDomainNormalizer.normalize(null)).isInstanceOf(ShopDomainNormalizer.NotShopifyAddress.class);
        String longHandle = "a".repeat(64);
        assertThatThrownBy(() -> ShopDomainNormalizer.normalize(longHandle + ".myshopify.com"))
            .isInstanceOf(ShopDomainNormalizer.NotShopifyAddress.class);
        assertThat(ShopDomainNormalizer.normalize("a".repeat(63) + ".myshopify.com").shopDomain())
            .isEqualTo("a".repeat(63) + ".myshopify.com");
    }
}
