package com.traceability.jobs;

import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.scheduling.cron.CronExpression;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DST (2026-11-05, after the 2026-10-29 stall). JobRunr 7.3.0's CronExpression.next() builds the next run as a
 * LOCAL time and converts it with atZone(), which takes the EARLIER offset for an ambiguous local time. In the
 * repeated hour after a fall-back (Cairo: 23:00–23:59 twice), the second pass's "next run" is therefore an
 * instant one hour in the PAST, so every poll (5 s) schedules a burst of runs for every minute-based job —
 * the ~6 s refiring seen on prod that night.
 *
 *   z1 every interval / hourly job (anything not a fixed daily wall-clock time) runs in UTC; every daily
 *      wall-clock job (fixed minute + hour) stays on Africa/Cairo. A new @Recurring must pick one of the two.
 *   z2 the bug itself: in the repeated Cairo hour, next() for "*&#47;3 * * * *" is in the past in Africa/Cairo
 *      and in the future in UTC.
 *   z3 the daily Cairo jobs are not in a repeated or skipped hour (Egypt's DST changes happen around midnight).
 */
class RecurringJobZonesTest {

    static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    static final Pattern NUMBER = Pattern.compile("\\d+");

    record Job(String id, String cron, String zone) {}

    static List<Job> recurringJobs() throws Exception {
        // Every class, whatever its @Conditional (most job classes need the job server switched on).
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override protected boolean isCandidateComponent(org.springframework.core.type.classreading.MetadataReader r) {
                return true;
            }
            @Override protected boolean isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition d) {
                return true;
            }
        };
        List<Job> jobs = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.traceability")) {
            Class<?> c = Class.forName(bd.getBeanClassName(), false, RecurringJobZonesTest.class.getClassLoader());
            if (c.getName().endsWith("Test") || c.getName().contains("Test$")) continue;
            Method[] methods = c.getDeclaredMethods();
            for (Method m : methods) {
                Recurring r = m.getAnnotation(Recurring.class);
                if (r != null) jobs.add(new Job(r.id(), resolveDefault(r.cron()), r.zoneId()));
            }
        }
        return jobs;
    }

    /** "${key:default}" → default (the annotation's own fallback). */
    static String resolveDefault(String cron) {
        if (cron.startsWith("${") && cron.endsWith("}")) {
            int colon = cron.indexOf(':');
            return cron.substring(colon + 1, cron.length() - 1);
        }
        return cron;
    }

    static boolean isDaily(String cron) {
        String[] f = cron.trim().split("\\s+");
        return f.length == 5 && NUMBER.matcher(f[0]).matches() && NUMBER.matcher(f[1]).matches()
            && f[2].equals("*") && f[3].equals("*") && f[4].equals("*");
    }

    @Test
    void z1_intervalJobsUtc_dailyJobsCairo() throws Exception {
        List<Job> jobs = recurringJobs();
        assertThat(jobs).as(jobs.stream().map(Job::id).sorted().toList().toString()).hasSizeGreaterThanOrEqualTo(31);
        List<String> wrong = new ArrayList<>();
        for (Job j : jobs) {
            String expected = isDaily(j.cron()) ? "Africa/Cairo" : "UTC";
            if (!expected.equals(j.zone())) wrong.add(j.id() + " (" + j.cron() + ") zone='" + j.zone() + "', want " + expected);
        }
        assertThat(wrong).as("recurring jobs on the wrong zone").isEmpty();
        assertThat(jobs.stream().filter(j -> isDaily(j.cron())).map(Job::id))
            .containsExactlyInAnyOrder("refund-details-purge", "return-photos-retention", "bosta-districts-refresh",
                "shopify-webhook-retention", "exception-daily-digest", "onboarding-purge", "analytics-inventory-sync");
    }

    static ZoneOffsetTransition fallBack2026() {
        ZoneOffsetTransition t = CAIRO.getRules().nextTransition(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(t.isOverlap()).as("Cairo's autumn transition is a fall-back").isTrue();
        return t;
    }

    @Test
    void z2_repeatedHour_cairoNextRunIsInThePast_utcIsNot() {
        ZoneOffsetTransition t = fallBack2026();
        Instant secondPass = t.getInstant().plusSeconds(5 * 60 + 30);   // 23:05:30 local, the 2nd time
        CronExpression every3 = CronExpression.create("*/3 * * * *");
        Instant created = Instant.parse("2026-01-01T00:00:00Z");

        Instant cairoNext = every3.next(created, secondPass, CAIRO);
        assertThat(cairoNext).as("the bug: an instant in the past → JobRunr schedules it right away, again and again")
            .isBefore(secondPass);

        Instant utcNext = every3.next(created, secondPass, ZoneOffset.UTC);
        assertThat(utcNext).isAfter(secondPass).isBefore(secondPass.plusSeconds(3 * 60 + 1));
    }

    @Test
    void z3_dailyCairoJobs_notInTheChangedHours() throws Exception {
        ZoneOffsetTransition autumn = fallBack2026();
        ZoneOffsetTransition spring = CAIRO.getRules().nextTransition(Instant.parse("2026-03-01T00:00:00Z"));
        int autumnHour = autumn.getDateTimeAfter().getHour();             // 23 (repeated)
        int springHour = spring.getDateTimeBefore().getHour();            // 0 (skipped)
        for (Job j : recurringJobs()) {
            if (!isDaily(j.cron())) continue;
            int hour = Integer.parseInt(j.cron().trim().split("\\s+")[1]);
            assertThat(hour).as(j.id()).isNotEqualTo(autumnHour).isNotEqualTo(springHour);
        }
    }
}
