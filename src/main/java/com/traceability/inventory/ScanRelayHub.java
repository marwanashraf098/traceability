package com.traceability.inventory;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * S6 — the open relay streams (SSE), one per pack session: in-memory, this instance only. A new
 * stream for a session replaces the previous one (a second tab never applies the same phone
 * scan twice). Holds no data and touches no database — {@link ScanPairingService} decides what
 * is delivered; this only writes to the stream. Heartbeat comment every 20 s.
 */
@Component
public class ScanRelayHub {

    private static final Logger log = LoggerFactory.getLogger(ScanRelayHub.class);

    static final long HEARTBEAT_SECONDS = 20;
    /** A stream lives 15 min; the tablet reconnects (and gets only events < 5 s old). */
    static final long STREAM_TIMEOUT_MS = 15 * 60 * 1000L;

    /** One subscriber per pack session; the lock serializes writes to its stream. */
    public static final class Subscriber {
        public final UUID sessionId;
        public final UUID tenantId;
        public final SseEmitter emitter;
        final Object lock = new Object();

        Subscriber(UUID sessionId, UUID tenantId, SseEmitter emitter) {
            this.sessionId = sessionId;
            this.tenantId = tenantId;
            this.emitter = emitter;
        }
    }

    private final Map<UUID, Subscriber> bySession = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "scan-relay-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public ScanRelayHub() {
        heartbeat.scheduleAtFixedRate(this::beat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        heartbeat.shutdownNow();
        bySession.values().forEach(s -> s.emitter.complete());
    }

    /** Opens the session's stream, closing any previous one for that session. */
    public Subscriber subscribe(UUID sessionId, UUID tenantId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Subscriber sub = new Subscriber(sessionId, tenantId, emitter);
        Subscriber previous = bySession.put(sessionId, sub);
        if (previous != null) previous.emitter.complete();
        Runnable drop = () -> bySession.remove(sessionId, sub);
        emitter.onCompletion(drop);
        emitter.onTimeout(drop);
        emitter.onError(e -> drop.run());
        return sub;
    }

    public Subscriber get(UUID sessionId) { return bySession.get(sessionId); }

    boolean connected(UUID sessionId) { return bySession.containsKey(sessionId); }

    /** Sends one named event; false (and the stream dropped) when it can't be written. */
    public boolean send(Subscriber sub, String name, Object data) {
        synchronized (sub.lock) {
            try {
                sub.emitter.send(SseEmitter.event().name(name).data(data));
                return true;
            } catch (IOException | IllegalStateException e) {
                bySession.remove(sub.sessionId, sub);
                sub.emitter.completeWithError(e);
                return false;
            }
        }
    }

    /** A pairing-status event to the session's stream, if one is open. */
    void sendStatus(UUID sessionId, Object status) {
        Subscriber sub = bySession.get(sessionId);
        if (sub != null) send(sub, "pairing", status);
    }

    /** Ends the session's stream (session ended). */
    void close(UUID sessionId) {
        Subscriber sub = bySession.remove(sessionId);
        if (sub != null) sub.emitter.complete();
    }

    private void beat() {
        for (Subscriber sub : bySession.values()) {
            synchronized (sub.lock) {
                try {
                    sub.emitter.send(SseEmitter.event().comment("hb"));
                } catch (IOException | IllegalStateException e) {
                    bySession.remove(sub.sessionId, sub);
                    log.debug("relay stream for session {} closed: {}", sub.sessionId, e.getMessage());
                }
            }
        }
    }
}
