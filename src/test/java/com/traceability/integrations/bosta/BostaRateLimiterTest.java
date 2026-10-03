package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Shared per-key Bosta rate limiter (2026-10-03).
 *
 *   rl1 concurrent callers on one key (discovery, link job, status poll) stay under the rate
 *   rl2 a 429's retry-after blocks the key for that long
 *   rl3 a user-facing caller goes ahead of waiting background callers
 *   rl4 cross-tenant: one key's budget / block never slows another key
 *   rl5 a wait longer than max-wait becomes a BostaRateLimitException (handled like a 429)
 *   rl6 the HTTP gateway goes through the limiter: a 429 from Bosta blocks that key's next call for
 *       its retry-after, and only that key's
 */
class BostaRateLimiterTest {

    @Test
    void rl1_concurrentCallers_stayUnderTheRate() throws Exception {
        BostaRateLimiter limiter = new BostaRateLimiter(10, 1, 60_000);
        List<Long> times = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<?>> fs = new ArrayList<>();
        for (int c = 0; c < 3; c++) {
            fs.add(pool.submit(() -> {
                for (int i = 0; i < 10; i++) {
                    limiter.acquire("key-broek", BostaRateLimiter.Priority.BACKGROUND);
                    times.add(System.nanoTime());
                }
            }));
        }
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        List<Long> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        assertThat(sorted).hasSize(30);
        for (int i = 0; i < sorted.size(); i++) {
            int inWindow = 0;
            for (int j = i; j < sorted.size() && sorted.get(j) - sorted.get(i) < 1_000_000_000L; j++) inWindow++;
            assertThat(inWindow).as("requests in any 1 s window").isLessThanOrEqualTo(11);   // rate + burst
        }
        assertThat(sorted.get(29) - sorted.get(0)).isGreaterThan(2_500_000_000L);
    }

    @Test
    void rl2_retryAfter_blocksTheKey() {
        BostaRateLimiter limiter = new BostaRateLimiter(100, 5, 60_000);
        limiter.onRateLimited("key-femine", 1);
        long start = System.nanoTime();
        limiter.acquire("key-femine", BostaRateLimiter.Priority.USER_FACING);
        assertThat(System.nanoTime() - start).as("waited out retry-after, even user-facing").isGreaterThan(900_000_000L);
    }

    @Test
    void rl3_userFacingGoesFirst() throws Exception {
        BostaRateLimiter limiter = new BostaRateLimiter(4, 1, 60_000);
        limiter.acquire("key-jumi", BostaRateLimiter.Priority.BACKGROUND);   // budget empty
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 3; i++) {
            int n = i;
            pool.submit(() -> { limiter.acquire("key-jumi", BostaRateLimiter.Priority.BACKGROUND); order.add("bg" + n); return null; });
        }
        Thread.sleep(50);   // the background callers are waiting
        Future<?> user = pool.submit(() -> {
            limiter.acquire("key-jumi", BostaRateLimiter.Priority.USER_FACING);
            order.add("user");
            return null;
        });
        user.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(order.indexOf("user")).as("the next token goes to the user-facing caller").isZero();
    }

    @Test
    void rl4_crossTenant_independent() {
        BostaRateLimiter limiter = new BostaRateLimiter(1, 1, 60_000);
        limiter.onRateLimited("key-a", 30);
        limiter.acquire("key-b", BostaRateLimiter.Priority.BACKGROUND);
        long start = System.nanoTime();
        limiter.acquire("key-c", BostaRateLimiter.Priority.BACKGROUND);
        assertThat(System.nanoTime() - start).isLessThan(200_000_000L);
    }

    @Test
    void rl5_tooLongAWait_isARateLimitSignal() {
        BostaRateLimiter limiter = new BostaRateLimiter(1, 1, 100);
        limiter.onRateLimited("key-snouts", 5);
        assertThatThrownBy(() -> limiter.acquire("key-snouts", BostaRateLimiter.Priority.BACKGROUND))
            .isInstanceOf(BostaRateLimitException.class);
    }

    @Test
    void rl6_gateway_429BlocksThatKeyForItsRetryAfter() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BostaRateLimiter limiter = new BostaRateLimiter(100, 5, 60_000);
        BostaHttpGateway gateway = new BostaHttpGateway(builder, new ObjectMapper(), "https://bosta.test", "v0", limiter);
        String ok = "{\"data\":{\"trackingNumber\":\"%s\",\"state\":{\"code\":24},\"type\":{\"code\":10,\"value\":\"Send\"}}}";
        server.expect(requestTo("https://bosta.test/api/v0/deliveries/9432163061")).andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"errorCode\":429,\"retryAfter\":1}"));
        server.expect(requestTo("https://bosta.test/api/v0/deliveries/8948149267"))
            .andRespond(withSuccess(String.format(ok, "8948149267"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://bosta.test/api/v0/deliveries/9432163061"))
            .andRespond(withSuccess(String.format(ok, "9432163061"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.fetchDelivery("key-broek", "9432163061"))
            .isInstanceOf(BostaRateLimitException.class);

        long t0 = System.nanoTime();
        gateway.fetchDelivery("key-femine", "8948149267");
        assertThat(System.nanoTime() - t0).as("another key is not blocked").isLessThan(300_000_000L);

        long t1 = System.nanoTime();
        assertThat(gateway.fetchDelivery("key-broek", "9432163061").stateCode()).isEqualTo(24);
        assertThat(System.nanoTime() - t1).as("the same key waited out retry-after").isGreaterThan(600_000_000L);
        server.verify();
    }
}
