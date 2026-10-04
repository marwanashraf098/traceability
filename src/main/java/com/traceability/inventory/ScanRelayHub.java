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
 * The open relay streams (SSE), one per PAIRING — i.e. one per tablet (Q1; S6 keyed them by pack
 * session): in-memory, this instance only. A new stream for a pairing replaces the previous one
 * (a second tab never applies the same phone scan twice). Holds no data and touches no database —
 * {@link ScanPairingService} decides what is delivered; this only writes to the stream.
 * Heartbeat comment every 20 s.
 */
@Component
public class ScanRelayHub {

    private static final Logger log = LoggerFactory.getLogger(ScanRelayHub.class);

    static final long HEARTBEAT_SECONDS = 20;
    /** A stream lives 15 min; the tablet reconnects (and gets only events < 5 s old). */
    static final long STREAM_TIMEOUT_MS = 15 * 60 * 1000L;

    /** One subscriber per pairing; the lock serializes writes to its stream. */
    public static final class Subscriber {
        public final UUID pairingId;
        public final UUID tenantId;
        public final SseEmitter emitter;
        final Object lock = new Object();

        Subscriber(UUID pairingId, UUID tenantId, SseEmitter emitter) {
            this.pairingId = pairingId;
            this.tenantId = tenantId;
            this.emitter = emitter;
        }
    }

    private final Map<UUID, Subscriber> byPairing = new ConcurrentHashMap<>();
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
        byPairing.values().forEach(s -> s.emitter.complete());
    }

    /** Opens the pairing's stream, closing any previous one for that pairing (a second tab replaces the first). */
    public Subscriber subscribe(UUID pairingId, UUID tenantId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Subscriber sub = new Subscriber(pairingId, tenantId, emitter);
        Subscriber previous = byPairing.put(pairingId, sub);
        if (previous != null) previous.emitter.complete();
        Runnable drop = () -> byPairing.remove(pairingId, sub);
        emitter.onCompletion(drop);
        emitter.onTimeout(drop);
        emitter.onError(e -> drop.run());
        return sub;
    }

    public Subscriber get(UUID pairingId) { return byPairing.get(pairingId); }

    boolean connected(UUID pairingId) { return byPairing.containsKey(pairingId); }

    /** Sends one named event; false (and the stream dropped) when it can't be written. */
    public boolean send(Subscriber sub, String name, Object data) {
        synchronized (sub.lock) {
            try {
                sub.emitter.send(SseEmitter.event().name(name).data(data));
                return true;
            } catch (IOException | IllegalStateException e) {
                byPairing.remove(sub.pairingId, sub);
                sub.emitter.completeWithError(e);
                return false;
            }
        }
    }

    /** A pairing-status event to the pairing's stream, if one is open. */
    void sendStatus(UUID pairingId, Object status) {
        Subscriber sub = byPairing.get(pairingId);
        if (sub != null) send(sub, "pairing", status);
    }

    /** Ends the pairing's stream (pairing revoked). */
    void close(UUID pairingId) {
        Subscriber sub = byPairing.remove(pairingId);
        if (sub != null) sub.emitter.complete();
    }

    private void beat() {
        for (Subscriber sub : byPairing.values()) {
            synchronized (sub.lock) {
                try {
                    sub.emitter.send(SseEmitter.event().comment("hb"));
                } catch (IOException | IllegalStateException e) {
                    byPairing.remove(sub.pairingId, sub);
                    log.debug("relay stream for pairing {} closed: {}", sub.pairingId, e.getMessage());
                }
            }
        }
    }
}
