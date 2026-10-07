package com.traceability.integrations.shopify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * "Find your store" typo check: does {@code <handle>.myshopify.com} exist? One GET of
 * {@code https://<handle>.myshopify.com/meta.json}, reading the status code only.
 *
 * Only a clear not-found (HTTP 404 — what Shopify answers for a shop that doesn't exist) blocks.
 * Everything else lets the merchant through to Shopify: 2xx / 3xx (a store, possibly redirecting to its
 * own domain or password page), any other status, a timeout or a network error. Shopify's own install
 * screen is the final word; this check only catches an obvious typo early.
 *
 * Safety: the URL is built ONLY from an already-normalised {@code <handle>.myshopify.com}
 * ({@link ShopDomainNormalizer#SHOP_DOMAIN}) — anything else is refused before any I/O; https only;
 * redirects are never followed; ~3 s connect and request timeouts; the body is discarded unread.
 */
public final class ShopifyStoreExistence {

    public enum Result { EXISTS, NOT_FOUND, INCONCLUSIVE }

    /** Fetches the status code of a URL; may throw. A seam for tests — production uses {@link #http()}. */
    @FunctionalInterface
    public interface StatusFetcher {
        int status(URI uri) throws Exception;
    }

    static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final StatusFetcher fetcher;

    public ShopifyStoreExistence(StatusFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public Result check(String shopDomain) {
        URI uri = metaJsonUri(shopDomain);   // throws before any I/O for anything but <handle>.myshopify.com
        int status;
        try {
            status = fetcher.status(uri);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Result.INCONCLUSIVE;
        }
        if (status == 404) return Result.NOT_FOUND;
        if (status >= 200 && status < 400) return Result.EXISTS;
        return Result.INCONCLUSIVE;
    }

    static URI metaJsonUri(String shopDomain) {
        if (!ShopDomainNormalizer.isShopDomain(shopDomain)) {
            throw new IllegalArgumentException("Store check is only for a normalised <handle>.myshopify.com");
        }
        return URI.create("https://" + shopDomain + "/meta.json");
    }

    /** The production fetcher: no redirects, 3 s timeouts, body discarded. */
    public static StatusFetcher http() {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(TIMEOUT)
            .build();
        return uri -> {
            if (!"https".equals(uri.getScheme())) throw new IllegalArgumentException("https only");
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET()
                .header("User-Agent", "Traced-StoreCheck/1.0").build();
            return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        };
    }
}
