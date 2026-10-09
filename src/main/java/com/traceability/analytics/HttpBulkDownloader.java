package com.traceability.analytics;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads a Shopify bulk operation's JSONL result (a signed, short-lived storage URL Shopify
 * returned — no token is sent). Read-only; explicit timeouts; https only.
 */
@Component
public class HttpBulkDownloader implements ShopifyInventoryReader.BulkDownloader {

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    @Override
    public List<String> lines(String url) throws Exception {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("bulk result URL must be https");
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<java.io.InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() / 100 != 2) throw new IllegalStateException("bulk result download answered " + res.statusCode());
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) out.add(line);
        }
        return out;
    }
}
