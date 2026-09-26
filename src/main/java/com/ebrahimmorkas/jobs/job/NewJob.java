package com.ebrahimmorkas.jobs.job;

import java.time.Instant;

/**
 * Everything needed to enqueue a job. A {@code null} runAt means "now" on the <em>database</em> clock:
 * workers compare run_at with PostgreSQL's now(), so using the app server's clock here would make jobs
 * invisible for as long as the two clocks disagree.
 */
public record NewJob(String type, String payload, int priority, int maxAttempts, Instant runAt, String idempotencyKey) {
}
