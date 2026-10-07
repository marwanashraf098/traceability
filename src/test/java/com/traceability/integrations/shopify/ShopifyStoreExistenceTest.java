package com.traceability.integrations.shopify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** "Find your store" typo check: only a clear 404 blocks; the URL can only ever be <handle>.myshopify.com. */
class ShopifyStoreExistenceTest {

    private final List<URI> calls = new ArrayList<>();

    private ShopifyStoreExistence answering(int status) {
        return new ShopifyStoreExistence(uri -> { calls.add(uri); return status; });
    }

    @Test
    void notFound_only_on404() {
        assertThat(answering(404).check("abc123.myshopify.com")).isEqualTo(ShopifyStoreExistence.Result.NOT_FOUND);
        assertThat(calls).singleElement().satisfies(u -> {
            assertThat(u.getScheme()).isEqualTo("https");
            assertThat(u.getHost()).isEqualTo("abc123.myshopify.com");
            assertThat(u.getPath()).isEqualTo("/meta.json");
            assertThat(u.getPort()).isEqualTo(-1);
            assertThat(u.getUserInfo()).isNull();
            assertThat(u.getQuery()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 301, 302})
    void storeAnswers_exists(int status) {
        assertThat(answering(status).check("abc123.myshopify.com")).isEqualTo(ShopifyStoreExistence.Result.EXISTS);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 402, 403, 410, 423, 429, 500, 502, 503, 504, 0, 999})
    void anyOtherAnswer_inconclusive_letsThrough(int status) {
        assertThat(answering(status).check("abc123.myshopify.com")).isEqualTo(ShopifyStoreExistence.Result.INCONCLUSIVE);
    }

    @Test
    void timeoutOrNetworkError_inconclusive() {
        assertThat(new ShopifyStoreExistence(u -> { throw new HttpTimeoutException("timed out"); })
            .check("abc123.myshopify.com")).isEqualTo(ShopifyStoreExistence.Result.INCONCLUSIVE);
        assertThat(new ShopifyStoreExistence(u -> { throw new java.net.ConnectException("refused"); })
            .check("abc123.myshopify.com")).isEqualTo(ShopifyStoreExistence.Result.INCONCLUSIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.com", "abc123.myshopify.com.evil.com", "ABC123.myshopify.com", "abc123.myshopify.com/x",
                            "abc123.myshopify.com:8443", "user@abc123.myshopify.com", "169.254.169.254", "localhost",
                            " abc123.myshopify.com", "abc123.myshopify.com#@evil.com"})
    void anythingButANormalisedShopDomain_refusedBeforeAnyIo(String host) {
        ShopifyStoreExistence check = answering(200);
        assertThatThrownBy(() -> check.check(host)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).as("no request made").isEmpty();
    }

    @Test
    void nullDomain_refused() {
        assertThatThrownBy(() -> answering(200).check(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productionFetcher_refusesNonHttps() {
        assertThatThrownBy(() -> ShopifyStoreExistence.http().status(URI.create("http://abc123.myshopify.com/meta.json")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
