package com.ebrahimmorkas.jobs.job;

import java.time.Instant;

/** Everything needed to enqueue a job. */
public record NewJob(String type, String payload, int priority, int maxAttempts, Instant runAt, String idempotencyKey) {
}
