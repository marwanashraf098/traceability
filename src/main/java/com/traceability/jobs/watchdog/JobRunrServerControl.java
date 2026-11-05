package com.traceability.jobs.watchdog;

import org.jobrunr.server.BackgroundJobServer;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Restarts the BackgroundJobServer through its own API: {@code stop()} then {@code start()} — what JobRunr's
 * {@code ServerZooKeeper.resetServer()} does itself. A stopped server's {@code stop()} returns at once.
 *
 * <p>Runs on a throw-away platform thread and is waited for at most {@code timeout}: {@code stop()} holds the
 * server's lifecycle write lock while it waits for workers (≤ 10 s), the zookeeper pool (≤ 10 s) and one DB
 * call; if anything in there hangs, the watchdog thread must not hang with it — it reports
 * "did not finish" and the escalation follows.
 */
public final class JobRunrServerControl implements JobServerWatchdog.ServerControl {

    private final Supplier<BackgroundJobServer> server;
    private final Duration timeout;

    public JobRunrServerControl(Supplier<BackgroundJobServer> server, Duration timeout) {
        this.server = server;
        this.timeout = timeout;
    }

    @Override
    public String restart() {
        BackgroundJobServer s = server.get();
        if (s == null) return "no BackgroundJobServer bean — nothing restarted";
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jobrunr-watchdog-restart");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<?> f = ex.submit(() -> {
                s.stop();
                s.start();
            });
            f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return "stopped and started (server " + s.getId() + ")";
        } catch (TimeoutException e) {
            return "restart did not finish within " + timeout.toSeconds() + " s (left running in the background)";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted while restarting";
        } catch (Exception e) {
            return "restart failed: " + (e.getCause() != null ? e.getCause() : e);
        } finally {
            ex.shutdown();
        }
    }
}
