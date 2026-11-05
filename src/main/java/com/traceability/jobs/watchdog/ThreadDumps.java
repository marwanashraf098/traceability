package com.traceability.jobs.watchdog;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Full thread dump as text. Two parts:
 * <ol>
 *   <li>Every platform thread with its WHOLE stack, locks held and the lock waited on (ThreadInfo.toString
 *       cuts stacks at 8 frames, so it isn't used). JobRunr threads (zookeeper pool, restart, anything named
 *       jobrunr / backgroundjob) come first, marked {@code [JOBRUNR]}.</li>
 *   <li>Every thread including VIRTUAL ones (JobRunr 7 workers on Java 21 are virtual threads; ThreadMXBean
 *       doesn't see them), via HotSpotDiagnosticMXBean.dumpThreads to a temp file, read back, capped.</li>
 * </ol>
 */
public final class ThreadDumps implements JobServerWatchdog.ThreadDumper {

    private static final int MAX_VIRTUAL_DUMP_CHARS = 4_000_000;

    static boolean isJobRunr(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("jobrunr") || n.contains("backgroundjob");
    }

    @Override
    public String dump() {
        StringBuilder b = new StringBuilder(64 * 1024);
        ThreadInfo[] infos = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
        Arrays.sort(infos, Comparator.comparing((ThreadInfo t) -> !isJobRunr(t.getThreadName()))
            .thenComparing(ThreadInfo::getThreadName));
        long jr = Arrays.stream(infos).filter(t -> isJobRunr(t.getThreadName())).count();
        b.append("===== THREAD DUMP (platform threads: ").append(infos.length)
         .append(", JobRunr: ").append(jr).append(") =====\n");
        for (ThreadInfo t : infos) append(b, t);
        b.append("===== FULL DUMP INCLUDING VIRTUAL THREADS (JobRunr workers) =====\n");
        b.append(virtualDump());
        b.append("===== END THREAD DUMP =====");
        return b.toString();
    }

    private static void append(StringBuilder b, ThreadInfo t) {
        b.append(isJobRunr(t.getThreadName()) ? "[JOBRUNR] " : "")
         .append('"').append(t.getThreadName()).append("\" id=").append(t.getThreadId())
         .append(t.isDaemon() ? " daemon" : "").append(' ').append(t.getThreadState());
        if (t.getLockName() != null) b.append(" on ").append(t.getLockName());
        if (t.getLockOwnerName() != null) b.append(" owned by \"").append(t.getLockOwnerName()).append('"');
        b.append('\n');
        StackTraceElement[] st = t.getStackTrace();
        MonitorInfo[] monitors = t.getLockedMonitors();
        for (int i = 0; i < st.length; i++) {
            b.append("\tat ").append(st[i]).append('\n');
            for (MonitorInfo m : monitors) {
                if (m.getLockedStackDepth() == i) b.append("\t- locked ").append(m).append('\n');
            }
        }
        LockInfo[] syncs = t.getLockedSynchronizers();
        if (syncs.length > 0) {
            b.append("\tLocked synchronizers:\n");
            for (LockInfo l : syncs) b.append("\t- ").append(l).append('\n');
        }
        b.append('\n');
    }

    private static String virtualDump() {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("traced-thread-dump");
            Path file = dir.resolve("threads.txt");      // must not exist yet
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                .dumpThreads(file.toAbsolutePath().toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
            String s = Files.readString(file, StandardCharsets.UTF_8);
            Files.deleteIfExists(file);
            return s.length() > MAX_VIRTUAL_DUMP_CHARS
                ? s.substring(0, MAX_VIRTUAL_DUMP_CHARS) + "\n… (cut at " + MAX_VIRTUAL_DUMP_CHARS + " chars)\n"
                : s;
        } catch (Exception e) {
            return "(virtual-thread dump unavailable: " + e + ")\n";
        } finally {
            if (dir != null) {
                try { Files.deleteIfExists(dir); } catch (Exception ignored) { }
            }
        }
    }
}
