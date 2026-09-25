package com.traceability.portal;

import org.jobrunr.jobs.annotations.Job;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Returns portal Step 4c-3 — the JobRunr entry point for booking one request's Bosta return
 * pickup. All logic (preconditions, claim, the single POST, result, read-back) lives in
 * {@link ReturnPickupBookingService#book}; that method never throws for a Bosta outcome, so
 * JobRunr never retries a booking on its own — a POST that may have reached Bosta is never
 * repeated automatically.
 */
@Component
public class ReturnPickupBookingJob {

    private final ReturnPickupBookingService booking;

    public ReturnPickupBookingJob(ReturnPickupBookingService booking) {
        this.booking = booking;
    }

    @Job(name = "Book Bosta return pickup — request %0", retries = 0)
    public void book(UUID requestId, UUID tenantId) {
        booking.book(requestId, tenantId);
    }
}
